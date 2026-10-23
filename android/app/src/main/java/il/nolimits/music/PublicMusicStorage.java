package il.nolimits.music;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Public, user-owned Downloads/NoLimitMusic/<playlist>/ files survive uninstall. */
final class PublicMusicStorage {
    private static final String ROOT = "NoLimitMusic";
    private static final String PREFS = "public_saved_playlists";
    private static final Map<String, Uri> INDEX = new HashMap<>();
    private static boolean indexed = false;
    private PublicMusicStorage() {}

    static String cleanName(String input) {
        String title = input == null ? "" : input.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ");
        title = title.replaceAll("^[. ]+|[. ]+$", "");
        if (title.length() > 70) title = title.substring(0, 70).trim();
        return title.isEmpty() ? "שירים אחרים" : title;
    }
    static String folder(String title) {
        return Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/" + cleanName(title) + "/";
    }
    static String album(Context context, String trackId) {
        String assigned = context.getSharedPreferences(PREFS, 0)
                .getString(trackId, null);
        if (assigned != null && !assigned.isEmpty()) return assigned;
        try {
            for (Catalog.Playlist playlist : Catalog.load(context))
                for (Catalog.Track track : playlist.tracks)
                    if (track.id.equals(trackId)) return cleanName(playlist.title);
        } catch (Exception ignored) {}
        return "שירים אחרים";
    }
    static void remember(Context context, String id, String title) {
        if (id != null && id.matches("[A-Za-z0-9_-]{11}"))
            context.getSharedPreferences(PREFS, 0)
                    .edit().putString(id, cleanName(title)).apply();
    }
    private static File oldPhoneFolder() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), ROOT);
    }
    private static Uri downloadsUri() {
        return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
    }
    private static synchronized void scan(Context context) {
        if (indexed) return;
        indexed = true;
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor c = context.getContentResolver().query(downloadsUri(),
                    new String[]{MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.RELATIVE_PATH},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/%"}, null)) {
                while (c != null && c.moveToNext()) {
                    String name = c.getString(1), path = c.getString(2);
                    if (path != null && path.startsWith(Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/")
                            && name != null && name.matches("[A-Za-z0-9_-]{11}\\.(mp3|mp4|m4a|lrc)")) {
                        INDEX.put(name, ContentUris.withAppendedId(downloadsUri(), c.getLong(0)));
                    }
                }
            } catch (Exception ignored) {
                // No permission to reindex another install's files: never delete them.
            }
        } else {
            File[] folders = oldPhoneFolder().listFiles();
            if (folders != null) for (File directory : folders) {
                File[] files = directory.listFiles();
                if (files == null) continue;
                for (File file : files)
                    if (file.isFile() && file.getName().matches("[A-Za-z0-9_-]{11}\\.(mp3|mp4|m4a|lrc)"))
                        INDEX.put(file.getName(), Uri.fromFile(file));
            }
        }
    }
    static synchronized Uri find(Context context, String id, String extension) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return null;
        scan(context);
        return INDEX.get(id + "." + extension);
    }
    static synchronized void downloaded(Context c, String id, String extension, Uri uri) {
        if (uri != null && id.matches("[A-Za-z0-9_-]{11}"))
            INDEX.put(id + "." + extension, uri);
    }
    static Uri saveFile(Context context, String id, String extension, File source) throws IOException {
        try (InputStream stream = new FileInputStream(source)) {
            return save(context, id, extension, stream);
        }
    }
    static Uri saveText(Context context, String id, String lyrics) throws IOException {
        byte[] bytes = lyrics.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (InputStream stream = new java.io.ByteArrayInputStream(bytes)) {
            return save(context, id, "lrc", stream);
        }
    }
    private static String mime(String ext) {
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "mp4" -> "video/mp4";
            default -> "text/plain";
        };
    }
    private static Uri save(Context context, String id, String ext, InputStream source) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}"))
            throw new IOException("Bad track ID");
        Uri existing = find(context,id,ext);
        if (existing != null) return existing;
        final String filename = id + "." + ext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime(ext));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder(album(context,id)));
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri destination = resolver.insert(downloadsUri(), values);
            if (destination == null) throw new IOException("Unable to reserve public file");
            boolean ok = false;
            try {
                try (OutputStream out = resolver.openOutputStream(destination, "w")) {
                    if (out == null) throw new IOException("Cannot write public download");
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = source.read(buffer)) != -1) out.write(buffer,0,n);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(destination, values, null, null);
                ok = true;
                downloaded(context,id,ext,destination);
                return destination;
            } finally {
                if (!ok) resolver.delete(destination,null,null);
            }
        }
        File directory = new File(oldPhoneFolder(),cleanName(album(context,id)));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create Downloads directory");
        File destination = new File(directory,filename);
        File temp = new File(directory,filename+".part");
        try {
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                int n; while ((n = source.read(buffer)) != -1) out.write(buffer,0,n);
            }
            if (!temp.renameTo(destination)) throw new IOException("Cannot publish downloaded file");
            Uri uri = Uri.fromFile(destination);
            downloaded(context,id,ext,uri);
            return uri;
        } finally { if (temp.exists()) temp.delete(); }
    }
    static synchronized boolean remove(Context context, String id, String extension) {
        Uri found = find(context,id,extension);
        if (found == null) return true;
        try {
            boolean ok;
            if ("content".equals(found.getScheme()))
                ok = context.getContentResolver().delete(found,null,null) > 0;
            else ok = new File(found.getPath()).delete();
            if (ok) INDEX.remove(id+"."+extension);
            return ok;
        } catch (Exception e) {return false;}
    }
}
