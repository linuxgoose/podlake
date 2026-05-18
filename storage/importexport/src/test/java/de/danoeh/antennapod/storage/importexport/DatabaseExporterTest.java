package de.danoeh.antennapod.storage.importexport;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class DatabaseExporterTest {

    @Before
    public void setUp() {
        UserPreferences.init(ApplicationProvider.getApplicationContext());
        UserPreferences.setFeedDefaultQueuePreferenceValue("{}");
    }

    @Test
    public void testWriteBackupPreferencesStoresFeedDefaultQueue() {
        UserPreferences.setFeedDefaultQueue(42, 7);

        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            DatabaseExporter.writeBackupPreferences(db);

            try (Cursor cursor = db.rawQuery("SELECT value FROM BackupPreferences WHERE preference=?",
                    new String[]{"feedDefaultQueue"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("{\"42\":7}", cursor.getString(0));
            }
        }
    }

    @Test
    public void testRestoreBackupPreferencesRestoresFeedDefaultQueue() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UserPreferences.setFeedDefaultQueue(42, 7);
            DatabaseExporter.writeBackupPreferences(db);

            UserPreferences.setFeedDefaultQueuePreferenceValue("{}");
            DatabaseExporter.restoreBackupPreferences(db);

            assertEquals(7, UserPreferences.getFeedDefaultQueue(42));
        }
    }

    @Test
    public void testRestoreBackupPreferencesClearsMissingFeedDefaultQueue() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UserPreferences.setFeedDefaultQueue(42, 7);

            DatabaseExporter.restoreBackupPreferences(db);

            assertEquals(-1, UserPreferences.getFeedDefaultQueue(42));
        }
    }
}


