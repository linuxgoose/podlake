package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.format.Formatter;
import android.util.Log;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class DatabaseExporter {
    private static final String TAG = "DatabaseExporter";
    private static final String TEMP_DB_NAME = PodDBAdapter.DATABASE_NAME + "_tmp";
    private static final String TABLE_NAME_BACKUP_PREFERENCES = "BackupPreferences";
    private static final String KEY_PREFERENCE = "preference";
    private static final String KEY_VALUE = "value";
    private static final String PREFERENCE_FEED_DEFAULT_QUEUE = "feedDefaultQueue";

    public static void exportToDocument(Uri uri, Context context) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "wt");
        if (pfd == null) {
            throw new IOException("Cannot open backup destination");
        }
        int bytesCopied = -1;
        int resultingFileSize = 0;
        try (FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor())) {
            bytesCopied = exportToStream(fileOutputStream, context);
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            resultingFileSize = (int) pfd.getStatSize();
            IOUtils.closeQuietly(pfd);
        }
        if (resultingFileSize != bytesCopied) {
            throw new IOException(String.format(
                    "Unable to write entire database. Expected to write %s, but wrote %s.",
                    Formatter.formatShortFileSize(context, bytesCopied),
                    Formatter.formatShortFileSize(context, resultingFileSize)));
        }
    }

    public static int exportToStream(FileOutputStream outFileStream, Context context) throws IOException {
        File currentDB = context.getDatabasePath(PodDBAdapter.DATABASE_NAME);
        if (!currentDB.exists()) {
            throw new IOException("Cannot access current database");
        }
        File tempDB = context.getDatabasePath(TEMP_DB_NAME);
        try {
            PodDBAdapter adapter = PodDBAdapter.getInstance();
            adapter.open();
            adapter.walCheckpoint();
            adapter.close();
            FileUtils.copyFile(currentDB, tempDB);
            try (SQLiteDatabase tempDbHandle = SQLiteDatabase.openDatabase(
                    tempDB.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                if (tempDbHandle.getVersion() != PodDBAdapter.VERSION) {
                    throw new IOException("Database version mismatch. Expected: " + PodDBAdapter.VERSION
                            + ", found: " + tempDbHandle.getVersion());
                }
                writeBackupPreferences(tempDbHandle);
            }
            try (InputStream src = new FileInputStream(tempDB)) {
                return IOUtils.copy(src, outFileStream);
            }
        } catch (IOException | SQLiteException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            boolean deleted = tempDB.delete();
            Log.d(TAG, "Deleted temp database file: " + deleted);
        }
    }

    public static void importBackup(Uri inputUri, Context context) throws IOException {
        InputStream inputStream = null;
        try {
            File tempDB = context.getDatabasePath(TEMP_DB_NAME);
            inputStream = context.getContentResolver().openInputStream(inputUri);
            if (inputStream == null) {
                throw new IOException("Cannot open backup source");
            }
            FileUtils.copyInputStreamToFile(inputStream, tempDB);

            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(tempDB.getAbsolutePath(),
                    null, SQLiteDatabase.OPEN_READONLY)) {
                if (db.getVersion() > PodDBAdapter.VERSION) {
                    throw new IOException(context.getString(R.string.import_no_downgrade));
                }
                restoreBackupPreferences(db);
            }

            File currentDB = context.getDatabasePath(PodDBAdapter.DATABASE_NAME);
            if (!currentDB.delete()) {
                throw new IOException("Unable to delete old database");
            }
            for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
                File sidecarFile = new File(currentDB.getAbsolutePath() + suffix);
                boolean success = sidecarFile.delete();
                Log.d(TAG, "Deleting sidecar file: " + sidecarFile.getAbsolutePath() + ", success: " + success);
            }
            FileUtils.moveFile(tempDB, currentDB);
        } catch (IOException | SQLiteException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    static void writeBackupPreferences(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME_BACKUP_PREFERENCES
                + " (" + KEY_PREFERENCE + " TEXT PRIMARY KEY, " + KEY_VALUE + " TEXT)");
        ContentValues values = new ContentValues();
        values.put(KEY_PREFERENCE, PREFERENCE_FEED_DEFAULT_QUEUE);
        values.put(KEY_VALUE, UserPreferences.getFeedDefaultQueuePreferenceValue());
        db.insertWithOnConflict(TABLE_NAME_BACKUP_PREFERENCES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    static void restoreBackupPreferences(SQLiteDatabase db) {
        String feedDefaultQueuePreference = "{}";
        try (Cursor cursor = db.query(TABLE_NAME_BACKUP_PREFERENCES,
                new String[]{KEY_VALUE},
                KEY_PREFERENCE + "=?",
                new String[]{PREFERENCE_FEED_DEFAULT_QUEUE},
                null,
                null,
                null)) {
            if (cursor.moveToFirst() && cursor.getString(0) != null) {
                feedDefaultQueuePreference = cursor.getString(0);
            }
        } catch (SQLiteException e) {
            Log.d(TAG, "Backup preferences table not found");
        }
        UserPreferences.setFeedDefaultQueuePreferenceValue(feedDefaultQueuePreference);
    }
}
