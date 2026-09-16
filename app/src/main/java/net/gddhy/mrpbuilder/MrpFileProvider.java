package net.gddhy.mrpbuilder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider（零依赖实现）：
 * 把应用 cache 目录下的产物（.mrp）以 content:// URI 提供给系统选择器 /
 * 模拟器 / 分享目标读取，避免 file:// 暴露（API 24+ 会抛 FileUriExposedException）。
 *
 * URI 形如：content://net.gddhy.mrpbuilder.fileprovider/<文件名>
 */
public class MrpFileProvider extends ContentProvider {

    public static Uri uriFor(File f) {
        return Uri.parse("content://net.gddhy.mrpbuilder.fileprovider/"
                + Uri.encode(f.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException("bad uri");
        File f = new File(getContext().getCacheDir(), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
