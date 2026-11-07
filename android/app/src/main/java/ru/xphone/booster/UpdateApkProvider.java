package ru.xphone.booster;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateApkProvider extends ContentProvider {
    static final String MIME = "application/vnd.android.package-archive";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return switch (uri.getEncodedPath()) {
            case "/update.apk" -> MIME;
            case "/diagnostics.txt" -> "text/plain";
            default -> null;
        };
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        if (file == null || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file = file(uri);
        if (file == null) return null;
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        if (file.isFile()) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                row.add(switch (column) {
                    case OpenableColumns.DISPLAY_NAME -> file.getName();
                    case OpenableColumns.SIZE -> file.length();
                    default -> null;
                });
            }
        }
        return cursor;
    }

    private File file(Uri uri) {
        String path = uri.getEncodedPath();
        if ("/update.apk".equals(path)) return AppUpdates.apkFile(getContext());
        if ("/diagnostics.txt".equals(path)) return Diagnostics.exportFile(getContext());
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
