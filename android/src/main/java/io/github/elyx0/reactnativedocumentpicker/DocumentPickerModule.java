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
import java.io.File;

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
  
    map.putString(FIELD_URI, getRealFilePath(mContext, uri));

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

  private static final String CONSTANT_PRIMARY = "primary";

  public static String getRealFilePath(Context context, final Uri uri) {
      if (null == uri)
          return null;
      final String scheme = uri.getScheme();
      String data = null;
      if (scheme == null)
          data = uri.getPath();
      else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
          data = uri.getPath();
      } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
          Cursor cursor = context.getContentResolver().query(uri, new String[] { MediaStore.Images.ImageColumns.DATA }, null, null, null);
          if (null != cursor) {
              if (cursor.moveToFirst()) {
                  int index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                  if (index > -1) {
                      data = cursor.getString(index);
                  }

              }
              cursor.close();
          }
          if (data == null) {
              data = getImageAbsolutePath(context, uri);
          }

      }
      return data;
    }

    public static Uri getUri(final String filePath) {
        return Uri.fromFile(new File(filePath));
    }

    @TargetApi(19)
    public static String getImageAbsolutePath(Context context, Uri imageUri) {
        if (context == null || imageUri == null)
            return null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, imageUri)) {
            if (isExternalStorageDocument(imageUri)) {
                String docId = DocumentsContract.getDocumentId(imageUri);
                String[] split = docId.split(":");
                String type = split[0];
                if (CONSTANT_PRIMARY.equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            } else if (isDownloadsDocument(imageUri)) {
                String id = DocumentsContract.getDocumentId(imageUri);
                Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                return getDataColumn(context, contentUri, null, null);
            } else if (isMediaDocument(imageUri)) {
                String docId = DocumentsContract.getDocumentId(imageUri);
                String[] split = docId.split(":");
                String type = split[0];
                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                String selection = MediaStore.Images.Media._ID + "=?";
                String[] selectionArgs = new String[] { split[1] };
                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        } // MediaStore (and general)
        else if ("content".equalsIgnoreCase(imageUri.getScheme())) {
            // Return the remote address
            if (isGooglePhotosUri(imageUri))
                return imageUri.getLastPathSegment();
            return getDataColumn(context, imageUri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(imageUri.getScheme())) {
            return imageUri.getPath();
        }
        return null;
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        String column = MediaStore.Images.Media.DATA;
        String[] projection = { column };
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
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
