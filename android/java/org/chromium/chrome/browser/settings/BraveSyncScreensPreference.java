/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.AppCompatImageView;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.chromium.base.ApiCompatibilityUtils;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveActivity;
import org.chromium.chrome.browser.BraveRewardsHelper;
import org.chromium.chrome.browser.BraveSyncWorker;

// TODO(sergz): Uncomment when we fully migrate on sync v2. Had a headache
// that pref calls should be called from UI thread only. It would lead
// to significant changes in the current javascript based BraveSyncWorker
// import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.BraveSyncScreensObserver;
import org.chromium.chrome.browser.qrreader.BarcodeTracker;
import org.chromium.chrome.browser.qrreader.BarcodeTrackerFactory;
import org.chromium.chrome.browser.qrreader.CameraSource;
import org.chromium.chrome.browser.qrreader.CameraSourcePreview;
import org.chromium.chrome.browser.settings.BravePreferenceFragment;
import org.chromium.chrome.browser.settings.SettingsActivity;
import org.chromium.chrome.browser.settings.SettingsLauncher;
import org.chromium.chrome.browser.sync.settings.ManageSyncSettings;
import org.chromium.chrome.browser.sync.BraveSyncDevices;
import org.chromium.chrome.browser.sync.BraveSyncService;
import org.chromium.ui.KeyboardVisibilityDelegate;
import org.chromium.ui.base.DeviceFormFactor;

import java.io.IOException;
import java.lang.Runnable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


/**
 * Settings fragment that allows to control Sync functionality.
 */
public class BraveSyncScreensPreference extends BravePreferenceFragment
      implements View.OnClickListener, SettingsActivity.OnBackPressedListener,
      CompoundButton.OnCheckedChangeListener, BarcodeTracker.BarcodeGraphicTrackerCallback,
      BraveSyncService.GetSettingsAndDevicesCallback,
      BraveSyncDevices.DeviceInfoChangedListener {

  private static final String TAG = "SYNC";
  // Permission request codes need to be < 256
  private static final int RC_HANDLE_CAMERA_PERM = 2;
  // Intent request code to handle updating play services if needed.
  private static final int RC_HANDLE_GMS = 9001;
  // For QR code generation
  private static final int WHITE = 0xFFFFFFFF;
  private static final int BLACK = 0xFF000000;
  private static final int WIDTH = 300;
  // For view sizes limit
  private static final int MAX_WIDTH = 512;
  private static final int MAX_HEIGHT = 1024;
  // Wait time out
  private static final int WAIT_TIMEOUT = 120000;
  // Timeout to show cancel button while loading devices on sync chain creation
  private static final int CANCEL_LOAD_BUTTON_TIMEOUT = 15*1000;

  // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
  // private BraveSyncService mSyncService;
  // private BraveSyncServiceObserver mSyncServiceObserver;
  private BraveSyncScreensObserver mSyncScreensObserver;
  private Switch mSyncSwitchBookmarks;
  // The have a sync code button displayed in the Sync view.
  private Button mScanChainCodeButton;
  private Button mStartNewChainButton;
  private Button mEnterCodeWordsButton;
  private Button mDoneButton;
  private Button mDoneLaptopButton;
  private Button mUseCameraButton;
  private Button mConfirmCodeWordsButton;
  private ImageButton mMobileButton;
  private ImageButton mLaptopButton;
  private ImageButton mPasteButton;
  private Button mCopyButton;
  private Button mAddDeviceButton;
  private Button mCancelLoadingButton;
  private Timer mCancelLoadingButtonUpdater;
  private Button mRemoveDeviceButton;
  private Button mShowCategoriesButton;
  private Button mQRCodeButton;
  private Button mCodeWordsButton;
  // Brave Sync message text view
  private TextView mBraveSyncTextViewInitial;
  private TextView mBraveSyncTextViewSyncChainCode;
  private TextView mBraveSyncTextViewAddMobileDevice;
  private TextView mBraveSyncTextViewAddLaptop;
  private TextView mBraveSyncWarningTextViewAddMobileDevice;
  private TextView mBraveSyncWarningTextViewAddLaptop;
  private TextView mBraveSyncTextDevicesTitle;
  private TextView mBraveSyncWordCountTitle;
  private TextView mBraveSyncAddDeviceCodeWords;
  private CameraSource mCameraSource;
  private CameraSourcePreview mCameraSourcePreview;
  private String mDeviceName = "";
  private ListView mDevicesListView;
  private ArrayAdapter<String> mDevicesAdapter;
  private List<String> mDevicesList;
  private ScrollView mScrollViewSyncInitial;
  private ScrollView mScrollViewSyncChainCode;
  private ScrollView mScrollViewSyncStartChain;
  private ScrollView mScrollViewAddMobileDevice;
  private ScrollView mScrollViewAddLaptop;
  private ScrollView mScrollViewEnterCodeWords;
  private ScrollView mScrollViewSyncDone;
  private LayoutInflater mInflater;
  private ImageView mQRCodeImage;
  //private CountDownTimer mTimeoutTimer;
  private LinearLayout mLayoutSyncStartChain;
  private EditText mCodeWords;
  private FrameLayout mLayoutMobile;
  private FrameLayout mLayoutLaptop;

  @Override
  public void deviceInfoChanged() {
      Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.deviceInfoChanged 000");
      if (mSyncScreensObserver != null) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.deviceInfoChanged will call mSyncScreensObserver.onDevicesAvailable()");
          mSyncScreensObserver.onDevicesAvailable();
      } else {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.deviceInfoChanged mSyncScreensObserver is null");
      }
  }
  boolean deviceInfoObserverSet = false;


  @Override
  public void onConfigurationChanged(Configuration newConfig) {
      super.onConfigurationChanged(newConfig);

      // Checks the orientation of the screen
      if (newConfig.orientation != Configuration.ORIENTATION_UNDEFINED
            && null != mCameraSourcePreview) {
          mCameraSourcePreview.stop();
          try {
              startCameraSource();
          } catch (SecurityException exc) {
          }
      }
      adjustImageButtons(newConfig.orientation);
  }

  @Override
  public View onCreateView(
          LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onCreateView");

      InvalidateCodephrase();

      if (ensureCameraPermission()) {
          createCameraSource(true, false);
      }
      mInflater = inflater;
      // Read which category we should be showing.
      return mInflater.inflate(R.layout.brave_sync_layout, container, false);
  }

  private boolean ensureCameraPermission() {
      if (ActivityCompat.checkSelfPermission(getActivity().getApplicationContext(), Manifest.permission.CAMERA)
              == PackageManager.PERMISSION_GRANTED){
          return true;
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          requestPermissions(
                  new String[]{Manifest.permission.CAMERA}, RC_HANDLE_CAMERA_PERM);
      }

      return false;
  }

  @Override
   public void onRequestPermissionsResult(int requestCode,
                                          String[] permissions,
                                          int[] grantResults) {
       if (requestCode != RC_HANDLE_CAMERA_PERM) {
           super.onRequestPermissionsResult(requestCode, permissions, grantResults);

           return;
       }

       if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
           // we have permission, so create the camerasource
           createCameraSource(true, false);

           return;
       }

       Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
               " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));
       // We still allow to enter words
       //getActivity().onBackPressed();
   }

  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onActivityCreated savedInstanceState=" + savedInstanceState);
      getActivity().setTitle(R.string.sign_in_sync);

      mDeviceName = "STUB_DEVICE_NAME";

      BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
      if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
          if (null == mSyncScreensObserver) {
              mSyncScreensObserver = new BraveSyncScreensObserver() {
                  @Override
                  public void onSyncError(String message) {
                      try {
                          if (null == getActivity()) {
                              return;
                          }
                          if (null != message && !message.isEmpty()) {
                              // if (message.equals("Credential server response 400. Signed request body of the client timestamp is required.")) {
                              //     message = getResources().getString(R.string.sync_requires_correct_time);
                              // }
                              message = " [" + message + "]";
                          }
                          final String messageFinal = (null == message) ? "" : message;
                          getActivity().runOnUiThread(new Runnable() {
                              @Override
                              public void run() {
//                                  cancelTimeoutTimer();
                                  showEndDialog(getResources().getString(R.string.sync_device_failure) + messageFinal);
                              }
                          });
                      } catch(Exception exc) {
                          Log.e(TAG, "onSyncError exception: " + exc);
                      }
                  }

                  @Override
                  public void onSeedReceived(/*String seedObs, */String seedHex, boolean fromCodeWords, boolean afterInitialization) {
                      Log.e(TAG, "onSeedReceived 000 seedHex=" + seedHex + " fromCodeWords="+fromCodeWords+" afterInitialization="+afterInitialization);
//onSeedReceived 000 seed=null fromCodeWords=false afterInitialization=true
                      try {
                          if (fromCodeWords) {
Log.e(TAG, "onSeedReceived 101");
                              assert !afterInitialization;

                              if (!isBarCodeValid(seedHex, true)) {
Log.e(TAG, "onSeedReceived 102");
                                  showEndDialog(getResources().getString(R.string.sync_device_failure));
                              }
Log.e(TAG, "onSeedReceived 103");
                              Log.i(TAG, "!!!received seedHex == " + seedHex);
// Below how it acts on DT
                              // HandleShowSetupUI => Prefs::SetSyncEnabled
                              //                   => brave_sync::prefs::Prefs::GetSeed()
                              //
                              //  => HandleSetSyncCode
                              //

                              if (null == getActivity()) {
                                  return;
                              }
                              getActivity().runOnUiThread(new Runnable() {
                                  @Override
                                  public void run() {
                                      //cancelTimeoutTimer();
                                      BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
                                      if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                                      //     mainActivity.mBraveSyncWorker.SetSyncEnabled(true);
                                      //     mainActivity.mBraveSyncWorker.InitSync(true, false);
                                          String wordsFromQr = mainActivity.mBraveSyncWorker.GetWordsFromSeedHex(seedHex);
Log.e(TAG, "onSeedReceived 104 wordsFromQr="+wordsFromQr);
                                          mainActivity.mBraveSyncWorker.SaveCodephrase(wordsFromQr);
                                          mainActivity.mBraveSyncWorker.OnDidClosePage();
                                      }
                                      setAppropriateView();
                                  }
                              });
                           } else if (afterInitialization) {
Log.e(TAG, "onSeedReceived 002");
                               assert !fromCodeWords;
                              //if (null != seed && !seed.isEmpty()) {
                              if (null != seedHex && !seedHex.isEmpty()) {
Log.e(TAG, "onSeedReceived 003 mScrollViewAddMobileDevice="+mScrollViewAddMobileDevice);
                                  if ((null != mScrollViewAddMobileDevice) && (View.VISIBLE == mScrollViewAddMobileDevice.getVisibility())) {
                                      // String[] seeds = seed.split(",");
                                      // if (seeds.length != 32) {
                                      //     Log.e(TAG, "Incorrect seed for QR code");
                                      // }
                                      // String qrData = "";
                                      // for (String s : seeds) {
                                      //     String hex = Integer.toHexString(Integer.parseInt(s.trim(), 10));
                                      //     if (hex.length() == 1) {
                                      //         hex = "0" + hex;
                                      //     }
                                      //     qrData += hex;
                                      // }
                                      final String qrDataFinal = seedHex;
Log.e(TAG, "onSeedReceived 003 qrDataFinal="+qrDataFinal);
                                      //Log.i(TAG, "Generate QR with data: " + qrDataFinal);
                                      new Thread(new Runnable() {
                                          @Override
                                          public void run() {
                                              // Generate QR code
                                              BitMatrix result;
                                              try {
                                                  result = new MultiFormatWriter().encode(qrDataFinal, BarcodeFormat.QR_CODE, WIDTH, WIDTH, null);
                                              } catch (WriterException e) {
                                                  Log.e(TAG, "QR code unsupported format: " + e);
                                                  return;
                                              }
                                              int w = result.getWidth();
                                              int h = result.getHeight();
                                              int[] pixels = new int[w * h];
                                              for (int y = 0; y < h; y++) {
                                                  int offset = y * w;
                                                  for (int x = 0; x < w; x++) {
                                                      pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
                                                  }
                                              }
                                              Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                              bitmap.setPixels(pixels, 0, WIDTH, 0, 0, w, h);
                                              getActivity().runOnUiThread(new Runnable() {
                                                  @Override
                                                  public void run() {
//                                                      cancelTimeoutTimer();
                                                      mQRCodeImage.setImageBitmap(bitmap);
                                                      mQRCodeImage.invalidate();
                                                  }
                                              });
                                          }
                                      }).start();
                                  } else if ((null != mScrollViewAddLaptop) && (View.VISIBLE == mScrollViewAddLaptop.getVisibility())) {
                                      if (null == getActivity()) {
                                          return;
                                      }
                                      getActivity().runOnUiThread(new Runnable() {
                                          @Override
                                          public void run() {
//                                              BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
                                              // if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                                              //     mainActivity.mBraveSyncWorker.GetCodeWords();
                                              // }
                                          }
                                      });
                                  }
                              }
                          } else {
                              Log.e(TAG, "Unknown flag on receiving seed");
                              assert false;
                          }
                      } catch(Exception exc) {
                          Log.e(TAG, "onSeedReceived exception: " + exc);
                      }
                  }

                  @Override
                  public void onCodeWordsReceived(String[] codeWords) {
                      Log.e(TAG, "onCodeWordsReceived 000 codeWords=" + Arrays.toString(codeWords));
                      try {
                          if (null == getActivity()) {
                              return;
                          }
                          getActivity().runOnUiThread(new Runnable() {
                              @Override
                              public void run() {
//                                  cancelTimeoutTimer();
//                                  BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
                                  // if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                                  //     mainActivity.mBraveSyncWorker.SetSyncEnabled(true);
                                  // }
                                  String words = "";
                                  for (int i = 0; i < codeWords.length; i++) {
                                      words = words + " " + codeWords[i].trim();
                                  }
Log.e(TAG, "onCodeWordsReceived words=" + words);
                                  mBraveSyncAddDeviceCodeWords.setText(words.trim());
                              }
                          });
                      } catch(Exception exc) {
                          Log.e(TAG, "onCodeWordsReceived exception: " + exc);
                      }
                  }

                  @Override
                  public void onDevicesAvailable() {
                      try {
Log.e(TAG, "onDevicesAvailable 000");
                          if (null == getActivity()) {
                              return;
                          }
                          getActivity().runOnUiThread(new Runnable() {
                              @Override
                              public void run() {
                                  if (View.VISIBLE != mScrollViewSyncDone.getVisibility()) {
                                      Log.w(TAG, "No need to load devices for other pages");
                                      return;
                                  }
                                  ArrayList<BraveSyncDevices.SyncDeviceInfo> deviceInfos = BraveSyncDevices.get().GetSyncDeviceList();
Log.e(TAG, "[BraveSync] onDevicesAvailable deviceInfos.size()="+deviceInfos.size());
                                  ViewGroup insertPoint = (ViewGroup) getView().findViewById(R.id.brave_sync_devices);
                                  insertPoint.removeAllViews();
                                  int index = 0;
                                  for (BraveSyncDevices.SyncDeviceInfo device : deviceInfos) {
                                      View separator = (View) mInflater.inflate(R.layout.menu_separator, null);
                                      View listItemView = (View) mInflater.inflate(R.layout.brave_sync_device, null);
                                      if (null != listItemView && null != separator && null != insertPoint) {
                                          TextView textView = (TextView) listItemView.findViewById(R.id.brave_sync_device_text);
                                          if (null != textView) {
                                              textView.setText(device.mName);
Log.e(TAG, "[BraveSync] onDevicesAvailable device.mName="+device.mName);
                                          }
                                          // TODO(alexey): remove R.id.brave_sync_remove_device from xml, because we cannot remove other devices with sync v2
                                          AppCompatImageView deleteButton = (AppCompatImageView) listItemView.findViewById(R.id.brave_sync_remove_device);
                                          if (null != deleteButton) {
                                              deleteButton.setVisibility(View.GONE);
                                          }

                                          //if (currentDeviceId.equals(device.mDeviceId)) {
                                          if (device.mIsCurrentDevice) {
                                               // Current device is deleted by button on the bottom
                                               //deleteButton.setVisibility(View.GONE);
                                               if (null != textView) {
                                                   // Highlight curret device
                                                   textView.setTextColor(ApiCompatibilityUtils.getColor(getActivity().getResources(), R.color.brave_theme_color));
                                                   String currentDevice = device.mName + " " + getResources().getString(R.string.brave_sync_this_device_text);
                                                   textView.setText(currentDevice);
                                              }
                                              // if (null != mRemoveDeviceButton) {
                                              //     mRemoveDeviceButton.setTag(device);
                                              //     mRemoveDeviceButton.setVisibility(View.VISIBLE);
                                              //     mRemoveDeviceButton.setEnabled(true);
                                              // }
                                          } else {
                                              // deleteButton.setTag(device);
                                              // deleteButton.setOnClickListener(v -> {
                                              //     BraveSyncWorker.ResolvedRecordToApply deviceToDelete = (BraveSyncWorker.ResolvedRecordToApply) v.getTag();
                                              //     deleteDeviceDialog(deviceToDelete.mDeviceName, deviceToDelete.mDeviceId, deviceToDelete.mObjectId, v);
                                              // });
                                          }
                                          insertPoint.addView(separator, index++);
                                          insertPoint.addView(listItemView, index++);
                                      }
                                  }

                                  if (index > 0) {
                                      mBraveSyncTextDevicesTitle.setText(getResources().getString(R.string.brave_sync_devices_title));
                                      View separator = (View) mInflater.inflate(R.layout.menu_separator, null);
                                      if (null != insertPoint && null != separator) {
                                          insertPoint.addView(separator, index++);
                                      }
                                  }

                                  // SharedPreferences sharedPref = getActivity().getApplicationContext().getSharedPreferences(BraveSyncWorker.PREF_NAME, 0);
                                  // String currentDeviceId = sharedPref.getString(BraveSyncWorker.PREF_DEVICE_ID, "");
                                  // Load other devices in chain
                                  // BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
                                  // if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                                  //     new Thread(new Runnable() {
                                  //         @Override
                                  //         public void run() {
                                  //             ArrayList<BraveSyncWorker.ResolvedRecordToApply> devices = mainActivity.mBraveSyncWorker.GetAllDevices();
                                  //             if (null == getActivity()) {
                                  //                 return;
                                  //             }
                                  //             getActivity().runOnUiThread(new Runnable() {
                                  //                 @Override
                                  //                 public void run() {
                                  //                     ViewGroup insertPoint = (ViewGroup) getView().findViewById(R.id.brave_sync_devices);
                                  //                     insertPoint.removeAllViews();
                                  //                     cancelTimeoutTimer();
                                  //                     int index = 0;
                                  //                     for (BraveSyncWorker.ResolvedRecordToApply device : devices) {
                                  //                         View separator = (View) mInflater.inflate(R.layout.menu_separator, null);
                                  //                         View listItemView = (View) mInflater.inflate(R.layout.brave_sync_device, null);
                                  //                         if (null != listItemView && null != separator && null != insertPoint) {
                                  //                             TextView textView = (TextView) listItemView.findViewById(R.id.brave_sync_device_text);
                                  //                             if (null != textView) {
                                  //                                 textView.setText(device.mDeviceName);
                                  //                             }
                                  //                             AppCompatImageView deleteButton = (AppCompatImageView) listItemView.findViewById(R.id.brave_sync_remove_device);
                                  //                             if (null != deleteButton) {
                                  //                                 if (currentDeviceId.equals(device.mDeviceId)) {
                                  //                                     // Current device is deleted by button on the bottom
                                  //                                     deleteButton.setVisibility(View.GONE);
                                  //                                     if (null != textView) {
                                  //                                         // Highlight curret device
                                  //                                         textView.setTextColor(ApiCompatibilityUtils.getColor(getActivity().getResources(), R.color.brave_theme_color));
                                  //                                         String currentDevice = device.mDeviceName + " " + getResources().getString(R.string.brave_sync_this_device_text);
                                  //                                         textView.setText(currentDevice);
                                  //                                     }
                                  //                                     if (null != mRemoveDeviceButton) {
                                  //                                         mRemoveDeviceButton.setTag(device);
                                  //                                         mRemoveDeviceButton.setVisibility(View.VISIBLE);
                                  //                                         mRemoveDeviceButton.setEnabled(true);
                                  //                                     }
                                  //                                 } else {
                                  //                                     deleteButton.setTag(device);
                                  //                                     deleteButton.setOnClickListener(v -> {
                                  //                                         BraveSyncWorker.ResolvedRecordToApply deviceToDelete = (BraveSyncWorker.ResolvedRecordToApply) v.getTag();
                                  //                                         deleteDeviceDialog(deviceToDelete.mDeviceName, deviceToDelete.mDeviceId, deviceToDelete.mObjectId, v);
                                  //                                     });
                                  //                                 }
                                  //                             }
                                  //
                                  //                             insertPoint.addView(separator, index++);
                                  //                             insertPoint.addView(listItemView, index++);
                                  //                         }
                                  //                     }
                                  //                     if (index > 0) {
                                  //                         dismissCancelLoadingButton();
                                  //                         mBraveSyncTextDevicesTitle.setText(getResources().getString(R.string.brave_sync_devices_title));
                                  //                         View separator = (View) mInflater.inflate(R.layout.menu_separator, null);
                                  //                         if (null != insertPoint && null != separator) {
                                  //                             insertPoint.addView(separator, index++);
                                  //                         }
                                  //                     }
                                  //                 }
                                  //             });
                                  //         }
                                  //     }).start();
                                  // }
                              }
                          });
                      } catch(Exception exc) {
                          Log.e(TAG, "onDevicesAvailable exception: " + exc);
                      }
                  }

                  @Override
                  public void onResetSync() {
                      try {
                          if (null == getActivity()) {
                              return;
                          }
                          getActivity().runOnUiThread(new Runnable() {
                              @Override
                              public void run() {
//                                  cancelTimeoutTimer();
                                  setAppropriateView();
                              }
                          });
                      } catch(Exception exc) {
                          Log.e(TAG, "onResetSync exception: " + exc);
                      }
                  }

                  @Override
                  public boolean shouldLoadDevices() {
                      if (null == getActivity() || View.VISIBLE != mScrollViewSyncDone.getVisibility()) {
                          // No need to load devices for other pages
                          return false;
                      }
                      return true;
                  }
              };
          }
          //mainActivity.mBraveSyncWorker.InitJSWebView(mSyncScreensObserver);
          //mainActivity.mBraveSyncWorker.InitV2(mSyncScreensObserver);
          mainActivity.mBraveSyncWorker.InitScreensObserver(mSyncScreensObserver);

          // TODO, AB: need to split sync state observer and devices observer
          mainActivity.mBraveSyncWorker.HandleShowSetupUI();
      }
      // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
      // Initialize mSyncServiceObserver
      // if (null != mSyncService) {
      //     if (null == mSyncServiceObserver) {
      //         mSyncServiceObserver = new BraveSyncServiceObserver() {
      //             @Override
      //             public void onSyncSetupError(String message) {
      //                 try {
      //                     if (null == getActivity()) {
      //                         return;
      //                     }
      //                     if (null != message && !message.isEmpty()) {
      //                         if (message.equals("Credential server response 400. Signed request body of the client timestamp is required.")) {
      //                             message = getResources().getString(R.string.brave_sync_requires_correct_time);
      //                         }
      //                         message = " [" + message + "]";
      //                     }
      //                     final String messageFinal = (null == message) ? "" : message;
      //                     getActivity().runOnUiThread(new Runnable() {
      //                         @Override
      //                         public void run() {
      //                             cancelTimeoutTimer();
      //                             showEndDialog(getResources().getString(R.string.brave_sync_device_failure) + messageFinal);
      //                         }
      //                     });
      //                 } catch(Exception exc) {
      //                     Log.e(TAG, "onSyncSetupError exception: " + exc);
      //                 }
      //             }

      //             @Override
      //             public void onSyncStateChanged() {
      //             }

      //             @Override
      //             public void onHaveSyncWords(String[] syncWords) {
      //                 try {
      //                     if (null == getActivity()) {
      //                         return;
      //                     }
      //                     getActivity().runOnUiThread(new Runnable() {
      //                         @Override
      //                         public void run() {
      //                             cancelTimeoutTimer();
      //                             mSyncService.onSetSyncEnabled(true);
      //                             String words = "";
      //                             for (int i = 0; i < syncWords.length; i++) {
      //                                 words = words + " " + syncWords[i].trim();
      //                             }
      //                             mBraveSyncAddDeviceCodeWords.setText(words.trim());
      //                         }
      //                     });
      //                 } catch(Exception exc) {
      //                     Log.e(TAG, "onCodeWordsReceived exception: " + exc);
      //                 }
      //             }
      //         };
      //     }
      // }

      mSyncSwitchBookmarks = (Switch) getView().findViewById(R.id.sync_bookmarks_switch);
      if (null != mSyncSwitchBookmarks) {
          mSyncSwitchBookmarks.setOnCheckedChangeListener(this);
      }

      mScrollViewSyncInitial = (ScrollView) getView().findViewById(R.id.view_sync_initial);
      mScrollViewSyncChainCode = (ScrollView) getView().findViewById(R.id.view_sync_chain_code);
      mScrollViewSyncStartChain = (ScrollView) getView().findViewById(R.id.view_sync_start_chain);
      mScrollViewAddMobileDevice = (ScrollView) getView().findViewById(R.id.view_add_mobile_device);
      mScrollViewAddLaptop = (ScrollView) getView().findViewById(R.id.view_add_laptop);
      mScrollViewEnterCodeWords = (ScrollView) getView().findViewById(R.id.view_enter_code_words);
      mScrollViewSyncDone = (ScrollView) getView().findViewById(R.id.view_sync_done);

      if (!DeviceFormFactor.isTablet()) {
          clearBackground(mScrollViewSyncInitial);
          clearBackground(mScrollViewSyncChainCode);
          clearBackground(mScrollViewSyncStartChain);
          clearBackground(mScrollViewAddMobileDevice);
          clearBackground(mScrollViewAddLaptop);
          clearBackground(mScrollViewEnterCodeWords);
          clearBackground(mScrollViewSyncDone);
      }

      mLayoutSyncStartChain = (LinearLayout) getView().findViewById(R.id.view_sync_start_chain_layout);

      mScanChainCodeButton = (Button) getView().findViewById(R.id.brave_sync_btn_scan_chain_code);
      if (mScanChainCodeButton != null) {
          mScanChainCodeButton.setOnClickListener(this);
      }

      mStartNewChainButton = (Button) getView().findViewById(R.id.brave_sync_btn_start_new_chain);
      if (mStartNewChainButton != null) {
          mStartNewChainButton.setOnClickListener(this);
      }

      mEnterCodeWordsButton = (Button) getView().findViewById(R.id.brave_sync_btn_enter_code_words);
      if (mEnterCodeWordsButton != null) {
          mEnterCodeWordsButton.setOnClickListener(this);
      }

      mQRCodeImage = (ImageView) getView().findViewById(R.id.brave_sync_qr_code_image);

      mDoneButton = (Button) getView().findViewById(R.id.brave_sync_btn_done);
      if (mDoneButton != null) {
          mDoneButton.setOnClickListener(this);
      }

      mDoneLaptopButton = (Button) getView().findViewById(R.id.brave_sync_btn_add_laptop_done);
      if (mDoneLaptopButton != null) {
          mDoneLaptopButton.setOnClickListener(this);
      }

      mUseCameraButton = (Button) getView().findViewById(R.id.brave_sync_btn_use_camera);
      if (mUseCameraButton != null) {
          mUseCameraButton.setOnClickListener(this);
      }

      mConfirmCodeWordsButton = (Button) getView().findViewById(R.id.brave_sync_confirm_code_words);
      if (mConfirmCodeWordsButton != null) {
          mConfirmCodeWordsButton.setOnClickListener(this);
      }

      mMobileButton = (ImageButton) getView().findViewById(R.id.brave_sync_btn_mobile);
      if (mMobileButton != null) {
          mMobileButton.setOnClickListener(this);
      }

      mLaptopButton = (ImageButton) getView().findViewById(R.id.brave_sync_btn_laptop);
      if (mLaptopButton != null) {
          mLaptopButton.setOnClickListener(this);
      }

      mPasteButton = (ImageButton) getView().findViewById(R.id.brave_sync_paste_button);
      if (mPasteButton != null) {
          mPasteButton.setOnClickListener(this);
      }

      mCopyButton = (Button) getView().findViewById(R.id.brave_sync_copy_button);
      if (mCopyButton != null) {
          mCopyButton.setOnClickListener(this);
      }

      mBraveSyncTextViewInitial = (TextView) getView().findViewById(R.id.brave_sync_text_initial);
      mBraveSyncTextViewSyncChainCode = (TextView) getView().findViewById(R.id.brave_sync_text_sync_chain_code);
      mBraveSyncTextViewAddMobileDevice = (TextView) getView().findViewById(R.id.brave_sync_text_add_mobile_device);
      mBraveSyncTextViewAddLaptop = (TextView) getView().findViewById(R.id.brave_sync_text_add_laptop);
      mBraveSyncWarningTextViewAddMobileDevice = (TextView) getView().findViewById(R.id.brave_sync_warning_text_add_mobile_device);
      mBraveSyncWarningTextViewAddLaptop = (TextView) getView().findViewById(R.id.brave_sync_warning_text_add_laptop);
      mBraveSyncTextDevicesTitle = (TextView) getView().findViewById(R.id.brave_sync_devices_title);
      mBraveSyncWordCountTitle = (TextView) getView().findViewById(R.id.brave_sync_text_word_count);
      mBraveSyncWordCountTitle.setText(getString(R.string.brave_sync_word_count_text, 0));
      mBraveSyncAddDeviceCodeWords = (TextView) getView().findViewById(R.id.brave_sync_add_device_code_words);
      setMainSyncText();
      mCameraSourcePreview = (CameraSourcePreview) getView().findViewById(R.id.preview);

      mAddDeviceButton = (Button) getView().findViewById(R.id.brave_sync_btn_add_device);
      if (null != mAddDeviceButton) {
          mAddDeviceButton.setOnClickListener(this);
      }

      mCancelLoadingButton = (Button) getView().findViewById(R.id.brave_sync_btn_cancel_loading);
      if (null != mCancelLoadingButton) {
          mCancelLoadingButton.setOnClickListener(this);
      }

      mRemoveDeviceButton = (Button) getView().findViewById(R.id.brave_sync_btn_remove_device);
      if (null != mRemoveDeviceButton) {
          mRemoveDeviceButton.setOnClickListener(this);
      }

      mShowCategoriesButton = (Button) getView().findViewById(R.id.brave_sync_btn_show_categories);
      if (null != mShowCategoriesButton) {
          mShowCategoriesButton.setOnClickListener(this);
      }

      mQRCodeButton = (Button) getView().findViewById(R.id.brave_sync_qr_code_off_btn);
      if (null != mQRCodeButton) {
          mQRCodeButton.setOnClickListener(this);
      }

      mCodeWordsButton = (Button) getView().findViewById(R.id.brave_sync_code_words_off_btn);
      if (null != mCodeWordsButton) {
          mCodeWordsButton.setOnClickListener(this);
      }

      mCodeWords = (EditText) getView().findViewById(R.id.code_words);

      mLayoutMobile = (FrameLayout) getView().findViewById(R.id.brave_sync_frame_mobile);
      mLayoutLaptop = (FrameLayout) getView().findViewById(R.id.brave_sync_frame_laptop);

      // mTimeoutTimer = new CountDownTimer(WAIT_TIMEOUT, WAIT_TIMEOUT) {
      //     @Override
      //     public void onTick(long millisUntilFinished) {
      //         // No action is required here
      //     }
      //     @Override
      //     public void onFinish() {
      //         if (null != mSyncScreensObserver) {
      //             mSyncScreensObserver.onDevicesAvailable();
      //         }
      //         showEndDialog(getResources().getString(R.string.brave_sync_time_out_message));
      //     }
      // };

      setAppropriateView();

      super.onActivityCreated(savedInstanceState);
  }

  private void setAppropriateView() {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.setAppropriateView 000");
      getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
      getActivity().setTitle(R.string.sync_category_title);
      // String seed = BravePrefServiceBridge.getInstance().getSyncSeed();
      // SharedPreferences sharedPref = getActivity().getApplicationContext().getSharedPreferences(BraveSyncWorker.PREF_NAME, 0);
      // String seed = sharedPref.getString(BraveSyncWorker.PREF_SEED, null);

      //String seed = null;
      // String seed = GetCodephrase();
      // Log.i(TAG, "setAppropriateView: seed == " + seed);
      // IsFirstSetupComplete
      //
      // if (null == seed || seed.isEmpty()) {
      BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity(); // TODO, AB pull out from rewards
      boolean firstSetupComplete = mainActivity.mBraveSyncWorker.IsFirstSetupComplete();
Log.e(TAG, "[BraveSync] setAppropriateView firstSetupComplete="+firstSetupComplete);
      if (!firstSetupComplete) {
          if (null != mCameraSourcePreview) {
              mCameraSourcePreview.stop();
          }
          if (null != mScrollViewSyncInitial) {
              adjustWidth(mScrollViewSyncInitial, false);
              mScrollViewSyncInitial.setVisibility(View.VISIBLE);
          }
          if (null != mScrollViewSyncChainCode) {
              mScrollViewSyncChainCode.setVisibility(View.GONE);
          }
          if (null != mScrollViewEnterCodeWords) {
              mScrollViewEnterCodeWords.setVisibility(View.GONE);
          }
          if (null != mScrollViewAddMobileDevice) {
              mScrollViewAddMobileDevice.setVisibility(View.GONE);
          }
          if (null != mScrollViewAddLaptop) {
              mScrollViewAddLaptop.setVisibility(View.GONE);
          }
          if (null != mScrollViewSyncStartChain) {
              mScrollViewSyncStartChain.setVisibility(View.GONE);
          }
          if (null != mScrollViewSyncDone) {
              mScrollViewSyncDone.setVisibility(View.GONE);
          }
          if (null != mCodeWords) {
              mCodeWords.setText("");
          }
          return;
      }
      setSyncDoneLayout();
  }

  private void setMainSyncText() {
      setSyncText(getResources().getString(R.string.brave_sync_official), getResources().getString(R.string.brave_sync_description_page_1_part_1) + "\n\n" +
                    getResources().getString(R.string.brave_sync_description_page_1_part_2), mBraveSyncTextViewInitial);
  }

  private void setQRCodeText() {
      setSyncText("", getResources().getString(R.string.brave_sync_qrcode_message_v2), mBraveSyncTextViewSyncChainCode);
  }

  private void setSyncText(String title, String message, TextView textView) {
      String text = "";
      if (title.length() > 0) {
          text = title + "\n\n";
      }
      text += message;
      SpannableString formatedText =  new SpannableString(text);
      formatedText.setSpan(new RelativeSizeSpan(1.25f), 0, title.length(), 0);
      textView.setText(formatedText);
  }

  /** OnClickListener for the clear button. We show an alert dialog to confirm the action */
  @Override
  public void onClick(View v) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onClick");
      if ((getActivity() == null) || (v != mScanChainCodeButton && v != mStartNewChainButton
          && v != mEnterCodeWordsButton && v != mDoneButton && v != mDoneLaptopButton
          && v != mUseCameraButton && v != mConfirmCodeWordsButton && v != mMobileButton && v != mLaptopButton
          && v != mPasteButton && v != mCopyButton && v != mRemoveDeviceButton && v != mShowCategoriesButton && v != mAddDeviceButton
          && v != mCancelLoadingButton && v != mQRCodeButton && v != mCodeWordsButton)) return;

      if (mScanChainCodeButton == v) {
Log.e(TAG, "[BraveSync] mScanChainCodeButton");
          setJoinExistingChainLayout();
      } else if (mStartNewChainButton == v) {
Log.e(TAG, "[BraveSync] mStartNewChainButton");
          // Creating a new chain
          GetCodephrase();
          setNewChainLayout();
      } else if (mMobileButton == v) {
Log.e(TAG, "[BraveSync] mMobileButton");
          setAddMobileDeviceLayout();
      } else if (mLaptopButton == v) {
Log.e(TAG, "[BraveSync] mLaptopButton");
          setAddLaptopLayout();
      } else if (mDoneButton == v) {
Log.e(TAG, "[BraveSync] mDoneButton");
          setSyncDoneLayout();
      } else if (mDoneLaptopButton == v) {
Log.e(TAG, "[BraveSync] mDoneLaptopButton");
          setSyncDoneLayout();
      } else if (mUseCameraButton == v) {
Log.e(TAG, "[BraveSync] mUseCameraButton");
          setJoinExistingChainLayout();
      } else if (mQRCodeButton == v) {
Log.e(TAG, "[BraveSync] mQRCodeButton");
          setAddMobileDeviceLayout();
      } else if (mCodeWordsButton == v) {
Log.e(TAG, "[BraveSync] mCodeWordsButton");
          setAddLaptopLayout();
      } else if (mPasteButton == v) {
          if (null != mCodeWords) {
              ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
              ClipData clipData = clipboard.getPrimaryClip();
              if (null != clipData && clipData.getItemCount() > 0) {
                  mCodeWords.setText(clipData.getItemAt(0).coerceToText(getActivity().getApplicationContext()));
              }
          }
      } else if (mCopyButton == v) {
          if (null != mBraveSyncAddDeviceCodeWords) {
              ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
              ClipData clip = ClipData.newPlainText("", mBraveSyncAddDeviceCodeWords.getText());
              clipboard.setPrimaryClip(clip);
              Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.brave_sync_copied_text), Toast.LENGTH_LONG).show();
          }
      } else if (mConfirmCodeWordsButton == v) {
;;;
Log.e(TAG, "[BraveSync] mConfirmCodeWordsButton");
          BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
          String[] words = mCodeWords.getText().toString().trim().replace("   ", " ").replace("\n", " ").split(" ");
          if (/*BraveSyncWorker.NICEWARE_WORD_COUNT != words.length && */BraveSyncWorker.BIP39_WORD_COUNT != words.length) {
Log.e(TAG, "[BraveSync] mConfirmCodeWordsButton - wrong words count");
              if (null != mSyncScreensObserver) {
                  mSyncScreensObserver.onSyncError(getResources().getString(R.string.brave_sync_word_count_error));
              }
              return;
          }
//24 words for now
          //
          if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
              String hexString = mainActivity.mBraveSyncWorker.GetSeedHexFromWords(String.join(" ", words));
Log.e(TAG, "[BraveSync] mConfirmCodeWordsButton - hexString="+hexString);
              if (hexString == null || hexString.isEmpty()) {
Log.e(TAG, "[BraveSync] mConfirmCodeWordsButton - wrong codephrase");
                  if (null != mSyncScreensObserver) {
                      mSyncScreensObserver.onSyncError(
                          "The codeprase is wrong" // TODO(AB): pull into translated string
                          //getResources().getString(R.string.brave_sync_word_count_error)
                      );
                  }
                  return;
              }
          }

          // Need to open a new activity assuming all is good
          // BraveSyncWorker.GetNumber => javascript:getBytesFromWords =>
          // => class JsObjectWordsToBytes cryptoOutput =>
          // => mSyncScreensObserver.onSeedReceived
          //
          // String seedHex, boolean fromCodeWords, boolean afterInitialization
          // mSyncScreensObserver.onSeedReceived(seedHex, true, false);
          mCodephrase = String.join(" ", words);
Log.e(TAG, "[BraveSync] mConfirmCodeWordsButton - mCodephrase="+mCodephrase);
          String seedHex = mainActivity.mBraveSyncWorker.GetSeedHexFromWords(GetCodephrase());
          if (null == seedHex || seedHex.isEmpty()) {
//                      startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_loading_data_title));
              // Init to receive new seed
//                      mainActivity.mBraveSyncWorker.InitSync(true, true);
          } else {
              //String seedHex, boolean fromCodeWords, boolean afterInitialization
              mSyncScreensObserver.onSeedReceived(seedHex, true, false);
          }

          // if (null != mainActivity && null != mainActivity.mBraveSyncWorker && null != words) {
          //     for (int i = 0; i < words.length; i++) {
          //         words[i] = words[i].trim();
          //     }
          //     mainActivity.mBraveSyncWorker.GetNumber(words);
          // }
          // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
          // String[] words = mCodeWords.getText().toString().trim().replace("   ", " ").replace("\n", " ").split(" ");
          // if (BraveSyncService.NICEWARE_WORD_COUNT != words.length && BraveSyncService.BIP39_WORD_COUNT != words.length) {
          //     if (null != mSyncServiceObserver) {
          //         mSyncServiceObserver.onSyncSetupError(getResources().getString(R.string.brave_sync_word_count_error));
          //     }
          //     return;
          // }
          // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
          // if (null != mSyncService && null != words) {
          //     for (int i = 0; i < words.length; i++) {
          //         words[i] = words[i].trim();
          //     }
          //     mSyncServiceObserver.onHaveSyncWords(words);
          // }
      } else if (mEnterCodeWordsButton == v) {
          getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
          if (null != mScrollViewSyncInitial) {
              mScrollViewSyncInitial.setVisibility(View.GONE);
          }
          if (null != mScrollViewAddMobileDevice) {
              mScrollViewAddMobileDevice.setVisibility(View.GONE);
          }
          if (null != mScrollViewAddLaptop) {
              mScrollViewAddLaptop.setVisibility(View.GONE);
          }
          if (null != mScrollViewSyncStartChain) {
              mScrollViewSyncStartChain.setVisibility(View.GONE);
          }
          if (null != mCameraSourcePreview) {
              mCameraSourcePreview.stop();
          }
          if (null != mScrollViewSyncChainCode) {
              mScrollViewSyncChainCode.setVisibility(View.GONE);
          }
          if (null != mScrollViewEnterCodeWords) {
              adjustWidth(mScrollViewEnterCodeWords, false);
              mScrollViewEnterCodeWords.setVisibility(View.VISIBLE);
          }
          getActivity().setTitle(R.string.brave_sync_code_words_title);
          if (null != mCodeWords && null != mBraveSyncWordCountTitle) {
              mCodeWords.addTextChangedListener(new TextWatcher() {
                   @Override
                   public void afterTextChanged(Editable s) {}

                   @Override
                   public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                   @Override
                   public void onTextChanged(CharSequence s, int start, int before, int count) {
                       int wordCount = mCodeWords.getText().toString().length();
                       if (0 != wordCount) {
                           String[] words = mCodeWords.getText().toString().trim().replace("   ", " ").replace("\n", " ").split(" ");
                           wordCount = words.length;
                       }
                       mBraveSyncWordCountTitle.setText(getString(R.string.brave_sync_word_count_text, wordCount));
                       mBraveSyncWordCountTitle.invalidate();
                   }
              });
          }
      } else if (mRemoveDeviceButton == v) {
Log.e(TAG, "[BraveSync] mRemoveDeviceButton");
          //BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
          //mainActivity.mBraveSyncWorker.HandleReset();
          //deleteDeviceDialog(deviceToDelete.mDeviceName, deviceToDelete.mDeviceId, deviceToDelete.mObjectId, mRemoveDeviceButton);
          deleteDeviceDialog("This device", "-1", "???", mRemoveDeviceButton);
      } else if (mShowCategoriesButton == v) {
Log.e(TAG, "[BraveSync] mShowCategoriesButton");
          SettingsLauncher.getInstance().launchSettingsPage(
                  getContext(), ManageSyncSettings.class);
      } else if (mAddDeviceButton == v) {
Log.e(TAG, "[BraveSync] mAddDeviceButton");
          setNewChainLayout();
      } else if (mCancelLoadingButton == v) {
Log.e(TAG, "[BraveSync] mCancelLoadingButton");
          cancelLoadingResetAndBack();
      }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onCheckedChanged isChecked="+isChecked);
      if ((getActivity() == null) || (buttonView != mSyncSwitchBookmarks)) {
          Log.w(TAG, "Unknown button");
          return;
      }
      // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
      // if (null != mSyncService) {
      //     if (buttonView == mSyncSwitchBookmarks) {
      //         mSyncService.onSetSyncBookmarks(isChecked);
      //     }
      // }
  }

  private void showMainSyncScrypt() {
      if (null != mScrollViewSyncInitial) {
          adjustWidth(mScrollViewSyncInitial, false);
          mScrollViewSyncInitial.setVisibility(View.VISIBLE);
      }
      if (null != mScrollViewAddMobileDevice) {
          mScrollViewAddMobileDevice.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddLaptop) {
          mScrollViewAddLaptop.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncStartChain) {
          mScrollViewSyncStartChain.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncChainCode) {
          mScrollViewSyncChainCode.setVisibility(View.GONE);
      }
      if (null != mScrollViewEnterCodeWords) {
          mScrollViewEnterCodeWords.setVisibility(View.GONE);
      }
      setMainSyncText();
  }

  // Handles the requesting of the camera permission.
  private void requestCameraPermission() {
      Log.w(TAG, "Camera permission is not granted. Requesting permission");

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          final String[] permissions = new String[]{Manifest.permission.CAMERA};

          requestPermissions(permissions, RC_HANDLE_CAMERA_PERM);
      }
  }

  @SuppressLint("InlinedApi")
  private void createCameraSource(boolean autoFocus, boolean useFlash) {
      Context context = getActivity().getApplicationContext();

      // A barcode detector is created to track barcodes.  An associated multi-processor instance
      // is set to receive the barcode detection results, track the barcodes, and maintain
      // graphics for each barcode on screen.  The factory is used by the multi-processor to
      // create a separate tracker instance for each barcode.
      BarcodeDetector barcodeDetector = new BarcodeDetector.Builder(context)
              .setBarcodeFormats(Barcode.ALL_FORMATS)
              .build();
      BarcodeTrackerFactory barcodeFactory = new BarcodeTrackerFactory(this);
      barcodeDetector.setProcessor(new MultiProcessor.Builder<>(barcodeFactory).build());

      if (!barcodeDetector.isOperational()) {
          // Note: The first time that an app using the barcode or face API is installed on a
          // device, GMS will download a native libraries to the device in order to do detection.
          // Usually this completes before the app is run for the first time.  But if that
          // download has not yet completed, then the above call will not detect any barcodes.
          //
          // isOperational() can be used to check if the required native libraries are currently
          // available.  The detectors will automatically become operational once the library
          // downloads complete on device.
          Log.w(TAG, "Detector dependencies are not yet available.");
      }

      // Creates and starts the camera.  Note that this uses a higher resolution in comparison
      // to other detection examples to enable the barcode detector to detect small barcodes
      // at long distances.
      DisplayMetrics metrics = new DisplayMetrics();
      getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);

      CameraSource.Builder builder = new CameraSource.Builder(getActivity().getApplicationContext(), barcodeDetector)
              .setFacing(CameraSource.CAMERA_FACING_BACK)
              .setRequestedPreviewSize(metrics.widthPixels, metrics.heightPixels)
              .setRequestedFps(24.0f);

      // make sure that auto focus is an available option
      builder = builder.setFocusMode(
              autoFocus ? Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE : null);

      mCameraSource = builder
              .setFlashMode(useFlash ? Camera.Parameters.FLASH_MODE_TORCH : null)
              .build();
  }

  private void startCameraSource() throws SecurityException {
      if (mCameraSource != null && mCameraSourcePreview.mCameraExist) {
          // check that the device has play services available.
          try {
              int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                      getActivity().getApplicationContext());
              if (code != ConnectionResult.SUCCESS) {
                  Dialog dlg =
                          GoogleApiAvailability.getInstance().getErrorDialog(getActivity(), code, RC_HANDLE_GMS);
                  if (null != dlg) {
                      dlg.show();
                  }
              }
          } catch (ActivityNotFoundException e) {
              Log.e(TAG, "Unable to start camera source.", e);
              mCameraSource.release();
              mCameraSource = null;

              return;
          }
          try {
              mCameraSourcePreview.start(mCameraSource);
          } catch (IOException e) {
              Log.e(TAG, "Unable to start camera source.", e);
              mCameraSource.release();
              mCameraSource = null;
          }
      }
  }

  @Override
  public void onResume() {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onResume");
      super.onResume();
      try {
          if (null != mCameraSourcePreview && View.GONE != mScrollViewSyncChainCode.getVisibility()) {
              startCameraSource();
          }
      } catch (SecurityException se) {
          Log.e(TAG,"Do not have permission to start the camera", se);
      } catch (RuntimeException e) {
          Log.e(TAG, "Could not start camera source.", e);
      }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
//??what fo ris this
      if (item.getItemId() == R.id.close_menu_id) {
//          cancelTimeoutTimer();
      }

      return super.onOptionsItemSelected(item);
  }

  @Override
  public void onPause() {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onPause");
      super.onPause();
      if (mCameraSourcePreview != null) {
          mCameraSourcePreview.stop();
      }
      InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
  }

  @Override
  public void onDestroy() {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onDestroy");
      super.onDestroy();
      if (mCameraSourcePreview != null) {
          mCameraSourcePreview.release();
      }
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onDestroy deviceInfoObserverSet="+deviceInfoObserverSet);
      if (deviceInfoObserverSet) {
          BraveSyncDevices.get().removeDeviceInfoChangedListener(this);
          deviceInfoObserverSet = false;
      }
  }

  private boolean isBarCodeValid(String barcode, boolean hexValue) {
Log.e(TAG, "isBarCodeValid 000");
Log.e(TAG, "isBarCodeValid barcode="+barcode);
Log.e(TAG, "isBarCodeValid hexValue="+hexValue);
Log.e(TAG, "isBarCodeValid barcode.length()="+barcode.length());
      if (hexValue && barcode.length() != 64) {
Log.e(TAG, "isBarCodeValid 001 return false");
          return false;
      } else if (!hexValue) {
          String[] split = barcode.split(",");
          if (split.length != 32) {
Log.e(TAG, "isBarCodeValid 002 return false");
              return false;
          }
      }
Log.e(TAG, "isBarCodeValid 003 return true");
      return true;
  }

  @Override
  public void onDetectedQrCode(Barcode barcode) {
Log.e(TAG, "onDetectedQrCode 000");
      if (barcode != null) {
          //Log.i(TAG, "!!!code == " + barcode.displayValue);
          final String barcodeValue = barcode.displayValue;
Log.e(TAG, "onDetectedQrCode barcodeValue="+barcodeValue);
          if (!isBarCodeValid(barcodeValue, true)) {
              showEndDialog(getResources().getString(R.string.brave_sync_device_failure));
              showMainSyncScrypt();
              return;
          }
          String[] barcodeString = barcodeValue.replaceAll("..(?!$)", "$0 ").split(" ");
          String seed = "";
          for (int i = 0; i < barcodeString.length; i++) {
              if (0 != seed.length()) {
                  seed += ",";
              }
              seed += String.valueOf(Integer.parseInt(barcodeString[i], 16));
          }
Log.e(TAG, "onDetectedQrCode seed="+seed);

          String seedHex = barcodeValue;
Log.e(TAG, "onDetectedQrCode seedHex="+seedHex);

// String seedHex, boolean fromCodeWords, boolean afterInitialization
mSyncScreensObserver.onSeedReceived(seedHex, true, false);
;;;

          //Log.i(TAG, "!!!seed == " + seed);
          // Save seed and deviceId in preferences
          // BravePrefServiceBridge.getInstance().setSyncSeed(seed);
          // SharedPreferences sharedPref = getActivity().getApplicationContext().getSharedPreferences(BraveSyncWorker.PREF_NAME, 0);
          // SharedPreferences.Editor editor = sharedPref.edit();
          // editor.putString(BraveSyncWorker.PREF_SEED, seed);
          // editor.apply();
          getActivity().runOnUiThread(new Runnable() {
              @Override
              public void run() {
Log.e(TAG, "onDetectedQrCode runOnUiThread.run 000");
                  BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
                  // if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                  //     mainActivity.mBraveSyncWorker.SetSyncEnabled(true);
                  //     mainActivity.mBraveSyncWorker.InitSync(true, false);
                  // }
                  // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
                  // if (null != mSyncService) {
                  //     mSyncService.onSetSyncEnabled(true);
                  //     mSyncService.onSetupSyncNewToSync(mDeviceName);
                  // }
                  setAppropriateView();
              }
          });
      }
  }

  private void showEndDialog(String message) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.showEndDialog message="+message);
      AlertDialog.Builder alert = new AlertDialog.Builder(getActivity(), R.style.Theme_Chromium_AlertDialog);
      if (null == alert) {
          return;
      }
      DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int button) {
          }
      };
      AlertDialog alertDialog = alert
              .setTitle(getResources().getString(R.string.brave_sync_device))
              .setMessage(message)
              .setPositiveButton(R.string.ok, onClickListener)
              .create();
      alertDialog.getDelegate().setHandleNativeActionModesEnabled(false);
      alertDialog.show();
  }

  // private void startTimeoutTimerWithPopup(String message) {
  //     Toast.makeText(getActivity().getApplicationContext(), message, Toast.LENGTH_LONG).show();
  //     mTimeoutTimer.start();
  // }
  //
  // private void cancelTimeoutTimer() {
  //     mTimeoutTimer.cancel();
  // }

  private void deleteDeviceDialog(String deviceName, String deviceId, String deviceObjectId, View v) {
Log.e(TAG, "[BraveSync] deleteDeviceDialog 000 deviceName=" + deviceName);
        AlertDialog.Builder alert = new AlertDialog.Builder(getActivity(), R.style.Theme_Chromium_AlertDialog);
        if (null == alert) {
            return;
        }
        DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int button) {
                if (button == AlertDialog.BUTTON_POSITIVE) {
Log.e(TAG, "[BraveSync] deleteDeviceDialog.onClick BUTTON_POSITIVE");
                    BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
                    if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                    //     new Thread() {
                    //         @Override
                    //         public void run() {
                    //             mainActivity.mBraveSyncWorker.SetUpdateDeleteDeviceName(BraveSyncWorker.DELETE_RECORD, deviceName, deviceId, deviceObjectId);
                    //             mainActivity.mBraveSyncWorker.InterruptSyncSleep();
                    //         }
                    //     }.start();

                    // Would go into DeviceResolver => ResetSync => mSyncScreensObserver.onResetSync();

                    mainActivity.mBraveSyncWorker.HandleReset();
                    //v.setEnabled(false); - AB: revert this when have the device list

                    mSyncScreensObserver.onResetSync();


                    //     startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_delete_sent));
                    }
                    // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
                    // if (null != mSyncService) {
                    //     new Thread() {
                    //         @Override
                    //         public void run() {
                    //             mSyncService.onDeleteDevice(deviceId);
                    //         }
                    //     }.start();
                    //     v.setEnabled(false);
                    //     startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_delete_sent));
                    // }
                }
            }
        };
        AlertDialog alertDialog = alert
                .setTitle(getResources().getString(R.string.brave_sync_remove_device_text))
                .setMessage(getString(R.string.brave_sync_delete_device, deviceName))
                .setPositiveButton(R.string.ok, onClickListener)
                .setNegativeButton(R.string.cancel, onClickListener)
                .create();
        alertDialog.getDelegate().setHandleNativeActionModesEnabled(false);
        alertDialog.show();
  }

  private void setJoinExistingChainLayout() {
      if (null != mScrollViewSyncInitial) {
          mScrollViewSyncInitial.setVisibility(View.GONE);
      }
      if (null != mScrollViewEnterCodeWords) {
          mScrollViewEnterCodeWords.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddMobileDevice) {
          mScrollViewAddMobileDevice.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddLaptop) {
          mScrollViewAddLaptop.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncStartChain) {
          mScrollViewSyncStartChain.setVisibility(View.GONE);
      }
      setQRCodeText();
      getActivity().setTitle(R.string.brave_sync_scan_chain_code);
      if (null != mScrollViewSyncChainCode) {
          adjustWidth(mScrollViewSyncChainCode, false);
          mScrollViewSyncChainCode.setVisibility(View.VISIBLE);
      }
      if (null != mCameraSourcePreview) {
          int rc = ActivityCompat.checkSelfPermission(getActivity().getApplicationContext(), Manifest.permission.CAMERA);
          if (rc == PackageManager.PERMISSION_GRANTED) {
              try {
                startCameraSource();
              } catch (SecurityException exc) {
              }
          }
      }
  }

  private void setNewChainLayout() {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.setNewChainLayout");
      getActivity().setTitle(R.string.brave_sync_start_new_chain);
      if (null != mScrollViewSyncInitial) {
          mScrollViewSyncInitial.setVisibility(View.GONE);
      }
      if (null != mScrollViewEnterCodeWords) {
          mScrollViewEnterCodeWords.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddMobileDevice) {
          mScrollViewAddMobileDevice.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddLaptop) {
          mScrollViewAddLaptop.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncStartChain) {
          mScrollViewSyncStartChain.setVisibility(View.VISIBLE);
      }
      if (null != mScrollViewSyncDone) {
          mScrollViewSyncDone.setVisibility(View.GONE);
      }
      adjustImageButtons(getActivity().getApplicationContext().getResources().getConfiguration().orientation);
  }

  private void cancelLoadingResetAndBack() {
      // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
      // if (null != mSyncService) {
      //     mSyncService.onResetSync();
      // }
      // BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
      // if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
      //     mainActivity.mBraveSyncWorker.ResetSync();
      // }
  }

  private String mCodephrase;
  public String GetCodephrase() {
Log.e(TAG, "[BraveSync] GetCodephrase 000 mCodephrase="+mCodephrase);
    if (mCodephrase == null || mCodephrase.isEmpty()) {
      BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
Log.e(TAG, "[BraveSync] GetCodephrase - ask mainActivity.mBraveSyncWorker");
      mCodephrase = mainActivity.mBraveSyncWorker.GetCodephrase2();
Log.e(TAG, "[BraveSync] GetCodephrase 003");
    }
Log.e(TAG, "[BraveSync] GetCodephrase mCodephrase="+mCodephrase);
    String[] codeWordsArray = mCodephrase.split(" "); // TODO, AB: don't split
Log.e(TAG, "[BraveSync] GetCodephrase 004");
    mSyncScreensObserver.onCodeWordsReceived(codeWordsArray);
Log.e(TAG, "[BraveSync] GetCodephrase 005");
    return mCodephrase;
  }
  public void InvalidateCodephrase() {
Log.e(TAG, "[BraveSync] InvalidateCodephrase 000");
    mCodephrase = null;
  }

  private void setAddMobileDeviceLayout() {
      getActivity().setTitle(R.string.brave_sync_btn_mobile);
      if (null != mBraveSyncTextViewAddMobileDevice) {
          setSyncText(getResources().getString(R.string.brave_sync_scan_sync_code),
                        getResources().getString(R.string.brave_sync_add_mobile_device_text_part_1) + "\n\n" +
                        getResources().getString(R.string.brave_sync_add_mobile_device_text_part_2) + "\n", mBraveSyncTextViewAddMobileDevice);
      }
      if (null != mBraveSyncWarningTextViewAddMobileDevice) {
        String braveSyncCodeWarning = getResources().getString(R.string.brave_sync_code_warning);
        SpannableString braveSyncCodeWarningSpanned = new SpannableString(braveSyncCodeWarning);

        ForegroundColorSpan foregroundSpan = new ForegroundColorSpan(getResources().getColor(R.color.default_red));
        braveSyncCodeWarningSpanned.setSpan(foregroundSpan, 0, braveSyncCodeWarningSpanned.length()-1, 0);
        mBraveSyncWarningTextViewAddMobileDevice.setText(braveSyncCodeWarningSpanned);
      }
      if (null != mScrollViewSyncInitial) {
          mScrollViewSyncInitial.setVisibility(View.GONE);
      }
      if (null != mScrollViewEnterCodeWords) {
          mScrollViewEnterCodeWords.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddMobileDevice) {
          adjustWidth(mScrollViewAddMobileDevice, false);
          mScrollViewAddMobileDevice.setVisibility(View.VISIBLE);
      }
      if (null != mScrollViewAddLaptop) {
          mScrollViewAddLaptop.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncStartChain) {
          mScrollViewSyncStartChain.setVisibility(View.GONE);
      }
      getActivity().runOnUiThread(new Runnable() {
          @Override
          public void run() {
              // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
              // if (null != mSyncService) {
              //     String seed = BravePrefServiceBridge.getInstance().getSyncSeed();
              //     if (null == seed || seed.isEmpty()) {
              //         startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_loading_data_title));
              //         // Init to receive new seed
              //         mSyncService.onSetupSyncNewToSync(mDeviceName);
              //     }
              // }

              BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
              if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                  // SharedPreferences sharedPref = getActivity().getApplicationContext().getSharedPreferences(BraveSyncWorker.PREF_NAME, 0);
                  // String seed = sharedPref.getString(BraveSyncWorker.PREF_SEED, null);
Log.e(TAG, "[BraveSync] setAddMobileDeviceLayout GetCodephrase()=" + GetCodephrase());
                  String seedHex = mainActivity.mBraveSyncWorker.GetSeedHexFromWords(GetCodephrase());
Log.e(TAG, "[BraveSync] setAddMobileDeviceLayout seedHex=" + seedHex);
                  if (null == seedHex || seedHex.isEmpty()) {
//                      startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_loading_data_title));
                      // Init to receive new seed
//                      mainActivity.mBraveSyncWorker.InitSync(true, true);
                  } else {
                      //String seedHex, boolean fromCodeWords, boolean afterInitialization
                      mSyncScreensObserver.onSeedReceived(seedHex, false, true);
                  }
              }
          }
      });
  }

  private void setAddLaptopLayout() {
Log.e(TAG, "[BraveSync] setAddLaptopLayout 000");
      getActivity().setTitle(R.string.brave_sync_btn_laptop);
      if (null != mBraveSyncTextViewAddLaptop) {
          setSyncText(getResources().getString(R.string.brave_sync_add_laptop_text_title),
                        getResources().getString(R.string.brave_sync_add_laptop_text_part_1) + "\n\n" +
                        getResources().getString(R.string.brave_sync_add_laptop_text_part_2_new) + "\n", mBraveSyncTextViewAddLaptop);
      }

      if (null != mBraveSyncWarningTextViewAddLaptop) {
        String braveSyncCodeWarning = getResources().getString(R.string.brave_sync_code_warning);
        SpannableString braveSyncCodeWarningSpanned = new SpannableString(braveSyncCodeWarning);

        ForegroundColorSpan foregroundSpan = new ForegroundColorSpan(getResources().getColor(R.color.default_red));
        braveSyncCodeWarningSpanned.setSpan(foregroundSpan, 0, braveSyncCodeWarningSpanned.length()-1, 0);
        mBraveSyncWarningTextViewAddLaptop.setText(braveSyncCodeWarningSpanned);
      }

      if (null != mScrollViewSyncInitial) {
          mScrollViewSyncInitial.setVisibility(View.GONE);
      }
      if (null != mScrollViewEnterCodeWords) {
          mScrollViewEnterCodeWords.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddMobileDevice) {
          mScrollViewAddMobileDevice.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddLaptop) {
          adjustWidth(mScrollViewAddLaptop, false);
          mScrollViewAddLaptop.setVisibility(View.VISIBLE);
      }
      if (null != mScrollViewSyncStartChain) {
          mScrollViewSyncStartChain.setVisibility(View.GONE);
      }
      getActivity().runOnUiThread(new Runnable() {
          @Override
          public void run() {
Log.e(TAG, "[BraveSync] setAddLaptopLayout.runOnUiThread.run 000");
              BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
              if (null != mainActivity && null != mainActivity.mBraveSyncWorker) {
                  // SharedPreferences sharedPref = getActivity().getApplicationContext().getSharedPreferences(BraveSyncWorker.PREF_NAME, 0);
                  // String seed = sharedPref.getString(BraveSyncWorker.PREF_SEED, null);
                  //String seedHex = null;
Log.e(TAG, "[BraveSync] setAddLaptopLayout.runOnUiThread.run GetCodephrase()="+GetCodephrase());
                  String seedHex = mainActivity.mBraveSyncWorker.GetSeedHexFromWords(GetCodephrase());
Log.e(TAG, "[BraveSync] setAddLaptopLayout.runOnUiThread.run seedHex="+seedHex);
                  if (null == seedHex || seedHex.isEmpty()) {
                      //startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_loading_data_title));
                      // Init to receive new seed
                      //mainActivity.mBraveSyncWorker.InitSync(true, true);
                  } else {
                      //String seedHex, boolean fromCodeWords, boolean afterInitialization
                      mSyncScreensObserver.onSeedReceived(seedHex, false, true);
                  }
              }
              // TODO(sergz): use service when we migrate to sync v2
              // if (null != mSyncService) {
              //     String seed = BravePrefServiceBridge.getInstance().getSyncSeed();
              //     if (null == seed || seed.isEmpty()) {
              //         startTimeoutTimerWithPopup(getResources().getString(R.string.brave_sync_loading_data_title));
              //         // Init to receive new seed
              //         mSyncService.onSetupSyncNewToSync(mDeviceName);
              //     }
              // }
          }
      });
  }

  private void scheduleCancelButton() {
      // String deviceId = BravePrefServiceBridge.getInstance().getSyncDeviceId();
      // SharedPreferences sharedPref = getActivity().getApplicationContext().getSharedPreferences(BraveSyncWorker.PREF_NAME, 0);
      // String deviceId = sharedPref.getString(BraveSyncWorker.PREF_DEVICE_ID, null);
      String deviceId = null;
      boolean syncChainExists = (deviceId != null && !deviceId.isEmpty());
      if (!syncChainExists) {
          mCancelLoadingButtonUpdater = new Timer();
          mCancelLoadingButtonUpdater.schedule(new TimerTask() {
              @Override
              public void run() {
                  ThreadUtils.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
//                          mCancelLoadingButton.setVisibility(View.VISIBLE);
                      }
                  });
              }
          }, CANCEL_LOAD_BUTTON_TIMEOUT);
      }
  }

  private void dismissCancelLoadingButton() {
      mCancelLoadingButton.setVisibility(View.GONE);
      if (mCancelLoadingButtonUpdater != null) {
          mCancelLoadingButtonUpdater.cancel();
      }
  }

  private void setSyncDoneLayout() {
Log.e(TAG, "[BraveSync] setSyncDoneLayout 000");
      BraveActivity mainActivity = BraveRewardsHelper.getBraveActivity();
      assert (null != mainActivity && null != mainActivity.mBraveSyncWorker);
      mainActivity.mBraveSyncWorker.SaveCodephrase(GetCodephrase());
      mainActivity.mBraveSyncWorker.OnDidClosePage();

Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.setSyncDoneLayout deviceInfoObserverSet="+deviceInfoObserverSet);
      if (!deviceInfoObserverSet) {
          BraveSyncDevices.get().addDeviceInfoChangedListener(this);
          deviceInfoObserverSet = true;
      }

      getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
      getActivity().setTitle(R.string.sync_category_title);
      if (null != mCameraSourcePreview) {
          mCameraSourcePreview.stop();
      }
      if (null != mScrollViewSyncInitial) {
          mScrollViewSyncInitial.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncChainCode) {
          mScrollViewSyncChainCode.setVisibility(View.GONE);
      }
      if (null != mScrollViewEnterCodeWords) {
          mScrollViewEnterCodeWords.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddMobileDevice) {
          mScrollViewAddMobileDevice.setVisibility(View.GONE);
      }
      if (null != mScrollViewAddLaptop) {
          mScrollViewAddLaptop.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncStartChain) {
          mScrollViewSyncStartChain.setVisibility(View.GONE);
      }
      if (null != mScrollViewSyncDone) {
          adjustWidth(mScrollViewSyncDone, false);
          mScrollViewSyncDone.setVisibility(View.VISIBLE);
      }
      // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
      // if (null != mSyncService) {
      //     if (null != mSyncSwitchBookmarks) {
      //         mSyncSwitchBookmarks.setChecked(false);
      //     }
      // }
      if (null != mRemoveDeviceButton) {
Log.e(TAG, "[BraveSync] setSyncDoneLayout mRemoveDeviceButton.setVisibility(View.GONE)");
          // It should become visible as soon as we get all devices info
//          mRemoveDeviceButton.setVisibility(View.GONE); - AB: want to see the buton to test LEAVE
      }
      // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
      // if (null != mSyncService) {
      //     mBraveSyncTextDevicesTitle.setText(getResources().getString(R.string.brave_sync_loading_devices_title));
      // }


      if (null != mainActivity/* && null != mainActivity.mBraveSyncWorker*/) {
          mBraveSyncTextDevicesTitle.setText(getResources().getString(R.string.brave_sync_loading_devices_title));
//          mainActivity.mBraveSyncWorker.InterruptSyncSleep();
      }

      scheduleCancelButton();

      if (null != mSyncScreensObserver) {
          mSyncScreensObserver.onDevicesAvailable();
      }
  }

  private void adjustWidth(View view, boolean special) {
      if (DeviceFormFactor.isTablet()) {
          DisplayMetrics metrics = new DisplayMetrics();
          getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
          LayoutParams params = view.getLayoutParams();
          if (special) {
              params.width = metrics.widthPixels * 3 / 5;
              params.height = metrics.heightPixels * 3 / 5;
          } else {
              int width = (metrics.widthPixels > metrics.heightPixels) ? metrics.widthPixels : metrics.heightPixels;
              width = width / 2;
              params.width = width;
              params.height = LayoutParams.MATCH_PARENT;
          }
          view.setLayoutParams(params);
      }
  }

  private void adjustImageButtons(int orientation) {
      if ((null != mLayoutSyncStartChain) && (null != mScrollViewSyncStartChain) && (View.VISIBLE == mScrollViewSyncStartChain.getVisibility())) {
          adjustWidth(mScrollViewSyncStartChain, true);
          LayoutParams params = mLayoutMobile.getLayoutParams();
          if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
              mLayoutSyncStartChain.setOrientation(LinearLayout.HORIZONTAL);
              params.width = 0;
              params.height = LayoutParams.MATCH_PARENT;
          } else {
              mLayoutSyncStartChain.setOrientation(LinearLayout.VERTICAL);
              params.width = LayoutParams.MATCH_PARENT;
              params.height = 0;
          }
          mLayoutMobile.setLayoutParams(params);
          mLayoutLaptop.setLayoutParams(params);
      }
  }

  private void clearBackground(View view) {
      if (null != view) {
          view.setBackgroundColor(Color.TRANSPARENT);
      }
  }

  private Context getBaseApplicationContext() {
      Context context = ContextUtils.getApplicationContext();
      if (context instanceof ContextWrapper) {
          return ((ContextWrapper) context).getBaseContext();
      } else {
          return context;
      }
  }

  // Handles 'Back' button. Returns true if it is handled, false otherwise.
  @Override
  public boolean onBackPressed() {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onBackPressed");
      if ((View.VISIBLE == mScrollViewSyncChainCode.getVisibility())
      || (View.VISIBLE == mScrollViewSyncStartChain.getVisibility())) {
          setAppropriateView();
          return true;
      } else if ((View.VISIBLE == mScrollViewAddMobileDevice.getVisibility())
      || (View.VISIBLE == mScrollViewAddLaptop.getVisibility())) {
          setNewChainLayout();
          return true;
      } else if (View.VISIBLE == mScrollViewEnterCodeWords.getVisibility()) {
          setJoinExistingChainLayout();
          return true;
      }
      //cancelTimeoutTimer();
      return false;
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String s) {}

  @Override
  public void onGetSettingsAndDevices(ArrayList<BraveSyncService.ResolvedRecordToApply> devices) {
Log.e(TAG, "[BraveSync] BraveSyncScreensPreference.onGetSettingsAndDevices");
      try {
          if (null == getActivity()) {
              return;
          }
          getActivity()
                  .runOnUiThread(
                          new Runnable() {
                              @Override
                              public void run() {
                                  if (View.VISIBLE != mScrollViewSyncDone.getVisibility()) {
                                      Log.w(TAG, "No need to load devices for other pages");
                                      return;
                                  }
                                  // TODO(sergz): Uncomment sync service impl when we fully migrate on sync v2
                                  // String currentDeviceId = BravePrefServiceBridge.getInstance().getSyncDeviceId();
                                  // Load other devices in chain
                                  // if (null != mSyncService) {
                                  //     new Thread(new Runnable() {
                                  //         @Override
                                  //         public void run() {
                                  //             if (null == getActivity()) {
                                  //                 return;
                                  //             }
                                  //             getActivity().runOnUiThread(new Runnable() {
                                  //                 @Override
                                  //                 public void run() {
                                  //                     ViewGroup insertPoint = (ViewGroup) getView().findViewById(R.id.brave_sync_devices);
                                  //                     insertPoint.removeAllViews();
                                  //                     cancelTimeoutTimer();
                                  //                     int index = 0;
                                  //                     for (BraveSyncService.ResolvedRecordToApply device : devices) {
                                  //                         View separator = (View) mInflater.inflate(R.layout.menu_separator, null);
                                  //                         View listItemView = (View) mInflater.inflate(R.layout.brave_sync_device, null);
                                  //                         if (null != listItemView && null != separator && null != insertPoint) {
                                  //                             TextView textView = (TextView) listItemView.findViewById(R.id.brave_sync_device_text);
                                  //                             if (null != textView) {
                                  //                                 textView.setText(device.mDeviceName);
                                  //                             }
                                  //                             AppCompatImageView deleteButton = (AppCompatImageView) listItemView.findViewById(R.id.brave_sync_remove_device);
                                  //                             if (null != deleteButton) {
                                  //                                 if (currentDeviceId.equals(device.mDeviceId)) {
                                  //                                     // Current device is deleted by button on the bottom
                                  //                                     deleteButton.setVisibility(View.GONE);
                                  //                                     if (null != textView) {
                                  //                                         // Highlight curret device
                                  //                                         textView.setTextColor(ApiCompatibilityUtils.getColor(getActivity().getResources(), R.color.brave_theme_color));
                                  //                                         String currentDevice = device.mDeviceName + " " + getResources().getString(R.string.brave_sync_this_device_text);
                                  //                                         textView.setText(currentDevice);
                                  //                       }
                                  //                                     if (null != mRemoveDeviceButton) {
                                  //                                         mRemoveDeviceButton.setTag(device);
                                  //                                         mRemoveDeviceButton.setVisibility(View.VISIBLE);
                                  //                                         mRemoveDeviceButton.setEnabled(true);
                                  //                                     }
                                  //                                 } else {
                                  //                                     deleteButton.setTag(device);
                                  //                                     deleteButton.setOnClickListener(v -> {
                                  //                                         BraveSyncService.ResolvedRecordToApply deviceToDelete = (BraveSyncService.ResolvedRecordToApply) v.getTag();
                                  //                                         deleteDeviceDialog(deviceToDelete.mDeviceName, deviceToDelete.mDeviceId, deviceToDelete.mObjectId, v);
                                  //                                     });
                                  //                                 }
                                  //                             }

                                  //                             insertPoint.addView(separator, index++);
                                  //                             insertPoint.addView(listItemView, index++);
                                  //                         }
                                  //                     }
                                  //                     if (index > 0) {
                                  //                         dismissCancelLoadingButton();
                                  //                         mBraveSyncTextDevicesTitle.setText(getResources().getString(R.string.brave_sync_devices_title));
                                  //                         View separator = (View) mInflater.inflate(R.layout.menu_separator, null);
                                  //                         if (null != insertPoint && null != separator) {
                                  //                             insertPoint.addView(separator, index++);
                                  //                         }
                                  //                     }
                                  //                 }
                                  //             });
                                  //         }
                                  //     }).start();
                                  // }
                              }
                          });
      } catch (Exception exc) {
          Log.e(TAG, "onDevicesAvailable exception: " + exc);
      }
  }
}
