package io.github.elyx0.reactnativedocumentpicker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import android.content.Context;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import android.content.ContentUris;
import android.os.Environment;
import android.annotation.TargetApi;



/**
 * @see <a href="https://developer.android.com/guide/topics/providers/document-provider.html">android documentation</a>
 */
public class DocumentPickerModule extends ReactContextBaseJavaModule {
  private static final String NAME = "RNDocumentPicker";
  private static final int READ_REQUEST_CODE = 41;

  private static final String E_ACTIVITY_DOES_NOT_EXIST = "ACTIVITY_DOES_NOT_EXIST";
  private static final String E_FAILED_TO_SHOW_PICKER = "FAILED_TO_SHOW_PICKER";
  private static final String E_DOCUMENT_PICKER_CANCELED = "DOCUMENT_PICKER_CANCELED";
  private static final String E_UNABLE_TO_OPEN_FILE_TYPE = "UNABLE_TO_OPEN_FILE_TYPE";
  private static final String E_UNKNOWN_ACTIVITY_RESULT = "UNKNOWN_ACTIVITY_RESULT";
  private static final String E_INVALID_DATA_RETURNED = "INVALID_DATA_RETURNED";
  private static final String E_UNEXPECTED_EXCEPTION = "UNEXPECTED_EXCEPTION";

  private static final String OPTION_TYPE = "type";
  private static final String OPTION_MULIPLE = "multiple";

  private static final String FIELD_URI = "uri";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_SIZE = "size";

  public static final String DOCUMENTS_DIR = "documents";
  public static final String AUTHORITY =  "YOUR_AUTHORITY.provider";

  private final ActivityEventListener activityEventListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      if (requestCode == READ_REQUEST_CODE) {
        if (promise != null) {
          onShowActivityResult(resultCode, data, promise);
          promise = null;
        }
      }
    }
  };

  private String[] readableArrayToStringArray(ReadableArray readableArray) {
    int l = readableArray.size();
    String[] array = new String[l];
    for (int i = 0; i < l; ++i) {
      array[i] = readableArray.getString(i);
    }
    return array;
  }

  private Promise promise;
  private Context mContext;

  public DocumentPickerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mContext = reactContext;
    reactContext.addActivityEventListener(activityEventListener);
  }

  @Override
  public void onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy();
    getReactApplicationContext().removeActivityEventListener(activityEventListener);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void pick(ReadableMap args, Promise promise) {
    Activity currentActivity = getCurrentActivity();

    if (currentActivity == null) {
      promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Current activity does not exist");
      return;
    }

    this.promise = promise;

    try {
      Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);

      intent.setType("*/*");
      if (!args.isNull(OPTION_TYPE)) {
        ReadableArray types = args.getArray(OPTION_TYPE);
        if (types.size() > 1) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String[] mimeTypes = readableArrayToStringArray(types);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
          } else {
            Log.e(NAME, "Multiple type values not supported below API level 19");
          }
        } else if (types.size() == 1) {
          intent.setType(types.getString(0));
        }
      }

      boolean multiple = !args.isNull(OPTION_MULIPLE) && args.getBoolean(OPTION_MULIPLE);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
      }

      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
        intent = Intent.createChooser(intent, null);
      }

      currentActivity.startActivityForResult(intent, READ_REQUEST_CODE, Bundle.EMPTY);
    } catch (ActivityNotFoundException e) {
      this.promise.reject(E_UNABLE_TO_OPEN_FILE_TYPE, e.getLocalizedMessage());
      this.promise = null;
    } catch (Exception e) {
      e.printStackTrace();
      this.promise.reject(E_FAILED_TO_SHOW_PICKER, e.getLocalizedMessage());
      this.promise = null;
    }
  }

  public void onShowActivityResult(int resultCode, Intent data, Promise promise) {
    if (resultCode == Activity.RESULT_CANCELED) {
      promise.reject(E_DOCUMENT_PICKER_CANCELED, "User canceled document picker");
    } else if (resultCode == Activity.RESULT_OK) {
      Uri uri = null;
      ClipData clipData = null;

      if (data != null) {
        uri = data.getData();
        clipData = data.getClipData();
      }

      try {
        WritableArray results = Arguments.createArray();

        if (uri != null) {
          results.pushMap(getMetadata(uri));
        } else if (clipData != null && clipData.getItemCount() > 0) {
          final int length = clipData.getItemCount();
          for (int i = 0; i < length; ++i) {
            ClipData.Item item = clipData.getItemAt(i);
            results.pushMap(getMetadata(item.getUri()));
          }
        } else {
          promise.reject(E_INVALID_DATA_RETURNED, "Invalid data returned by intent");
          return;
        }

        promise.resolve(results);
      } catch (Exception e) {
        promise.reject(E_UNEXPECTED_EXCEPTION, e.getLocalizedMessage(), e);
      }
    } else {
      promise.reject(E_UNKNOWN_ACTIVITY_RESULT, "Unknown activity result: " + resultCode);
    }
  }

  private WritableMap getMetadata(Uri uri) {
    WritableMap map = Arguments.createMap();
  
    map.putString(FIELD_URI, getPath(mContext, uri));

    ContentResolver contentResolver = getReactApplicationContext().getContentResolver();

    map.putString(FIELD_TYPE, contentResolver.getType(uri));

    Cursor cursor = contentResolver.query(uri, null, null, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (!cursor.isNull(displayNameIndex)) {
          map.putString(FIELD_NAME, cursor.getString(displayNameIndex));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
          if (!cursor.isNull(mimeIndex)) {
            map.putString(FIELD_TYPE, cursor.getString(mimeIndex));
          }
        }

        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
        if (!cursor.isNull(sizeIndex)) {
          map.putInt(FIELD_SIZE, cursor.getInt(sizeIndex));
        }
      }
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }

    return map;
  }

    public static String getPath(final Context context, final Uri uri) {
        String absolutePath = getLocalPath(context, uri);
        return absolutePath != null ? absolutePath : uri.toString();
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static String getLocalPath(final Context context, final Uri uri) {

            Log.d("TAG" + " File -",
                    "Authority: " + uri.getAuthority() +
                            ", Fragment: " + uri.getFragment() +
                            ", Port: " + uri.getPort() +
                            ", Query: " + uri.getQuery() +
                            ", Scheme: " + uri.getScheme() +
                            ", Host: " + uri.getHost() +
                            ", Segments: " + uri.getPathSegments().toString()
            );

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // LocalStorageProvider
            if (isLocalStorageDocument(uri)) {
                // The path is the id
                return DocumentsContract.getDocumentId(uri);
            }
            // ExternalStorageProvider
            else if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                } else if ("home".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/documents/" + split[1];
                }
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);

                if (id != null && id.startsWith("raw:")) {
                    return id.substring(4);
                }

                String[] contentUriPrefixesToTry = new String[]{
                        "content://downloads/public_downloads",
                        "content://downloads/my_downloads"
                };

                for (String contentUriPrefix : contentUriPrefixesToTry) {
                    Uri contentUri = ContentUris.withAppendedId(Uri.parse(contentUriPrefix), Long.valueOf(id));
                    try {
                        String path = getDataColumn(context, contentUri, null, null);
                        if (path != null) {
                            return path;
                        }
                    } catch (Exception e) {}
                }

                // path could not be retrieved using ContentResolver, therefore copy file to accessible cache using streams
                String fileName = getFileName(context, uri);
                File cacheDir = getDocumentCacheDir(context);
                File file = generateFileName(fileName, cacheDir);
                String destinationPath = null;
                if (file != null) {
                    destinationPath = file.getAbsolutePath();
                    saveFileFromUri(context, uri, destinationPath);
                }

                return destinationPath;
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {

            // Return the remote address
            if (isGooglePhotosUri(uri)) {
                return uri.getLastPathSegment();
            }

            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    public static String getFileName(@NonNull Context context, Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        String filename = null;

        if (mimeType == null && context != null) {
            String path = getPath(context, uri);
            if (path == null) {
                filename = getName(uri.toString());
            } else {
                File file = new File(path);
                filename = file.getName();
            }
        } else {
            Cursor returnCursor = context.getContentResolver().query(uri, null,
                    null, null, null);
            if (returnCursor != null) {
                int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                returnCursor.moveToFirst();
                filename = returnCursor.getString(nameIndex);
                returnCursor.close();
            }
        }

        return filename;
    }

    public static File getDocumentCacheDir(@NonNull Context context) {
        File dir = new File(context.getCacheDir(), DOCUMENTS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        logDir(context.getCacheDir());
        logDir(dir);

        return dir;
    }

    @Nullable
    public static File generateFileName(@Nullable String name, File directory) {
        if (name == null) {
            return null;
        }

        File file = new File(directory, name);

        if (file.exists()) {
            String fileName = name;
            String extension = "";
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex > 0) {
                fileName = name.substring(0, dotIndex);
                extension = name.substring(dotIndex);
            }

            int index = 0;

            while (file.exists()) {
                index++;
                name = fileName + '(' + index + ')' + extension;
                file = new File(directory, name);
            }
        }

        try {
            if (!file.createNewFile()) {
                return null;
            }
        } catch (IOException e) {
            Log.w("TAG", e);
            return null;
        }

        logDir(directory);

        return file;
    }

    private static void saveFileFromUri(Context context, Uri uri, String destinationPath) {
        InputStream is = null;
        BufferedOutputStream bos = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            bos = new BufferedOutputStream(new FileOutputStream(destinationPath, false));
            byte[] buf = new byte[1024];
            is.read(buf);
            do {
                bos.write(buf);
            } while (is.read(buf) != -1);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) is.close();
                if (bos != null) bos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void logDir(File dir) {
        Log.d("TAG", "Dir=" + dir);
        File[] files = dir.listFiles();
        for (File file : files) {
            Log.d("TAG", "File=" + file.getPath());
        }
    }

    public static String getName(String filename) {
        if (filename == null) {
            return null;
        }
        int index = filename.lastIndexOf('/');
        return filename.substring(index + 1);
    }



    private static String uriToPath(Uri uri) {
        List<String> list =  uri.getPathSegments();
        Log.d("list", list.toString());
      String slash = "/";
      StringBuilder sb = new StringBuilder();
      int i = 1;
      while (i < list.size() - 1) {
          sb.append(list.get(i));
          sb.append(slash);
          i++;
      }
      sb.append(list.get(i));
      String path = Environment.getExternalStorageDirectory() + "/" + sb.toString();
      File file = new File(path);
      if (file.exists()) {
          return path;
      }
      return null;
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column =  MediaStore.Files.FileColumns.DATA;
        String[] projection = { column };
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } catch (Exception e) {
          return uriToPath(uri);
        }
        finally {
            if (cursor != null)
                cursor.close();
            }
          return uriToPath(uri);
    }

    public static boolean isLocalStorageDocument(Uri uri) {
        return AUTHORITY.equals(uri.getAuthority());
    }

    /**
     * @param uri
     *                The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri
     *                The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri
     *                The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri
     *                The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    public static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }
}
