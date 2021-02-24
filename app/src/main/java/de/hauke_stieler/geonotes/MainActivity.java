package de.hauke_stieler.geonotes;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.ActionMenuItemView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.res.ResourcesCompat;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.views.MapView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import de.hauke_stieler.geonotes.common.FileHelper;
import de.hauke_stieler.geonotes.database.Database;
import de.hauke_stieler.geonotes.export.Exporter;
import de.hauke_stieler.geonotes.export.GeoJson;
import de.hauke_stieler.geonotes.map.Map;
import de.hauke_stieler.geonotes.map.MarkerWindow;
import de.hauke_stieler.geonotes.map.TouchDownListener;
import de.hauke_stieler.geonotes.photo.ThumbnailUtil;
import de.hauke_stieler.geonotes.settings.SettingsActivity;

public class MainActivity extends AppCompatActivity {

    private final int REQUEST_PERMISSIONS_REQUEST_CODE = 1;
    private final int REQUEST_CAMERA_PERMISSIONS_REQUEST_CODE = 2;
    static final int REQUEST_IMAGE_CAPTURE = 1;

    private Map map;
    private SharedPreferences preferences;
    private Database database;
    private Exporter exporter;

    // These fields exist to remember the photo data when the photo Intent is started. This is
    // because the Intent doesn't return anything and works asynchronously. In the result handler
    // only "null" is passed but we want to store the photo for the note, that's why we store the
    // data in these fields here. Ugly and horrorfying but that's how it works in the Android
    // world ...
    private static File lastPhotoFile;
    private static Long lastPhotoNoteId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Injector.registerActivity(this);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Set HTML text of copyright label
        ((TextView) findViewById(R.id.copyright)).setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView) findViewById(R.id.copyright)).setText(Html.fromHtml("© <a href=\"https://openstreetmap.org/copyright\">OpenStreetMap</a> contributors"));

        final Context context = getApplicationContext();

        requestPermissionsIfNecessary(new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA
        });

        database = Injector.get(Database.class);
        preferences = Injector.get(SharedPreferences.class);
        exporter = Injector.get(Exporter.class);

        createMap();
        loadPreferences();
    }

    private void createMap() {
        map = Injector.get(Map.class);

        addMapListener();
        addCameraListener();
    }

    void loadPreferences() {
        for (String key : preferences.getAll().keySet()) {
            preferenceChanged(preferences, key);
        }

        float lat = preferences.getFloat(getString(R.string.pref_last_location_lat), 0f);
        float lon = preferences.getFloat(getString(R.string.pref_last_location_lon), 0f);
        float zoom = preferences.getFloat(getString(R.string.pref_last_location_zoom), 2);

        map.setLocation(lat, lon, zoom);
    }

    private void preferenceChanged(SharedPreferences pref, String key) {
        if (getString(R.string.pref_zoom_buttons).equals(key)) {
            boolean showZoomButtons = pref.getBoolean(key, true);
            map.setZoomButtonVisibility(showZoomButtons);
        } else if (getString(R.string.pref_map_scaling).equals(key)) {
            float mapScale = pref.getFloat(key, 1.0f);
            map.setMapScaleFactor(mapScale);
        } else if (getString(R.string.pref_snap_note_gps).equals(key)) {
            boolean snapNoteToGps = pref.getBoolean(key, false);
            map.setSnapNoteToGps(snapNoteToGps);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.toolbar_btn_gps_follow:
                boolean followingLocationEnabled = !map.isFollowLocationEnabled();
                this.map.setLocationFollowMode(followingLocationEnabled);

                if (followingLocationEnabled) {
                    item.setIcon(R.drawable.ic_my_location);
                } else {
                    item.setIcon(R.drawable.ic_location_searching);
                }
                return true;
            case R.id.toolbar_btn_export:
                exporter.export();
                return true;
            case R.id.toolbar_btn_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPreferences();
        map.onResume();
    }

    @Override
    public void onPause() {
        map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        map.onDestroy();
        super.onDestroy();
    }

    private void requestPermissionsIfNecessary(String[] permissions) {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (!hasPermission(permission)) { // Permission is not granted
                permissionsToRequest.add(permission);
            }
        }
        if (permissionsToRequest.size() > 0) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS_REQUEST_CODE);
        }
    }

    private void addMapListener() {
        DelayedMapListener delayedMapListener = new DelayedMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                storeLocation();
                return true;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                storeLocation();
                return true;
            }
        }, 500);

        @SuppressLint("RestrictedApi")
        TouchDownListener touchDownListener = () -> {
            ActionMenuItemView menuItem = (ActionMenuItemView) findViewById(R.id.toolbar_btn_gps_follow);
            if (menuItem != null) {
                menuItem.setIcon(getResources().getDrawable(R.drawable.ic_location_searching));
            }
        };

        map.addMapListener(delayedMapListener, touchDownListener);
    }

    /**
     * Adds a listener for the camera button. The camera action can only be performed from within an activity.
     */
    private void addCameraListener() {
        MarkerWindow.RequestPhotoEventHandler requestPhotoEventHandler = (Long noteId) -> {
            if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // We don't have camera and/or storage permissions -> ask for them
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CAMERA_PERMISSIONS_REQUEST_CODE);
            } else {
                // We do have all permissions -> take photo
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                try {
                    // Create the File where the photo should go
                    lastPhotoFile = createImageFile();
                    lastPhotoNoteId = noteId;

                    Uri photoURI = FileHelper.getFileUri(this, MainActivity.lastPhotoFile);
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                    startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
                } catch (Exception e) {
                    Log.e("TakingPhoto", "Opening camera to take photo failed", e);
                }
            }
        };

        map.addRequestPhotoHandler(requestPhotoEventHandler);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // If photo-Intent was successful
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            addPhotoToDatabase(lastPhotoNoteId, lastPhotoFile);
            addPhotoToGallery(lastPhotoFile);
            map.addImagesToMarkerWindow();
        }
    }

    /**
     * Creates an empty file in the Environment.DIRECTORY_PICTURES directory.
     */
    private File createImageFile() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "geonotes_" + timeStamp;

        File storageDir = getExternalFilesDir("GeoNotes");
        File image = new File(storageDir, imageFileName + ".jpg");

        return image;
    }

    private void addPhotoToDatabase(Long noteId, File photoFile) {
        database.addPhoto(noteId, photoFile);

        int sizeInPixel = getResources().getDimensionPixelSize(R.dimen.ImageButton);

        try {
            ThumbnailUtil.writeThumbnail(sizeInPixel, photoFile);
        } catch (IOException e) {
            Toast.makeText(getApplicationContext(), "Creating thumbnail failed", Toast.LENGTH_SHORT);
        }
    }

    private void addPhotoToGallery(File photoFile) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, photoFile.getName());
        values.put(MediaStore.Images.Media.DISPLAY_NAME, photoFile.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpg");
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.DATE_TAKEN, photoFile.lastModified());
        values.put(MediaStore.Images.Media.DATA, photoFile.toString());

        getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }

    /**
     * Stores the current map location and zoom in the shared preferences.
     */
    private void storeLocation() {
        IGeoPoint location = map.getLocation();
        float zoom = map.getZoom();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat(getString(R.string.pref_last_location_lat), (float) location.getLatitude());
        editor.putFloat(getString(R.string.pref_last_location_lon), (float) location.getLongitude());
        editor.putFloat(getString(R.string.pref_last_location_zoom), zoom);
        editor.commit();
    }
}