package com.evernote.keyboardgeometrybuilder;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.evernote.espressokeyboard.Key;
import com.evernote.espressokeyboard.KeyInfo;
import com.evernote.espressokeyboard.KeyboardSwitcher;
import com.evernote.espressokeyboard.NavBarUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class KeyboardGeometry extends AppCompatActivity implements SoftKeyboardStateHelper.SoftKeyboardStateListener {
  public static final String TAG = "kgb";

  public static final int PIXEL_PITCH = 8;
  public static final boolean SHORT_SCENARIO = false;

  private TextView logView;
  private ScrollView scrollView;
  private EditText editText;
  ShellCommandRunner shellCommandRunner;
  private Handler handler;
  private boolean suppressEvents = false;

  TouchCommand currentCommand;

  Point navBarSize;
  Point screenSize;
  Point screenBottom;
  int keyboardTop;
  int spaceBarBottom;
  int keyHeight;
  int minKeyWidth;
  int rowPadding;
  int numRows;
  int orientation;
  boolean hasCompletion = false;

  public static final int EVENT_DELAY = 1000;

  HashMap<Key,KeyInfo> foundKeys = new HashMap<>();
  private SoftKeyboardStateHelper keyboardStateHelper;
  private TouchView touchView;
  boolean running = true;

  public static List<String> keyboards = null;
  public String currentKeyboard;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_keyboard_geometry);

    keyboardStateHelper = new SoftKeyboardStateHelper(this);
    keyboardStateHelper.addSoftKeyboardStateListener(this);

    handler = new Handler();

    scrollView = (ScrollView) findViewById(R.id.scrollView);
    logView = (TextView) findViewById(R.id.log);
    editText = (EditText) findViewById(R.id.input);

    navBarSize = NavBarUtil.getNavigationBarSize(this);
    screenSize = NavBarUtil.getRealScreenSize(this);

    orientation = getResources().getConfiguration().orientation;
    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
      screenBottom = new Point(screenSize.x / 2, screenSize.y - navBarSize.y);
    } else {
      screenBottom = new Point(screenSize.x / 2 - navBarSize.x, screenSize.y);
    }

    editText.requestFocus();

    editText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (suppressEvents) return;

        Log.d(TAG, "beforeTextChanged '" + s + "' - " + start + " - " + after + " - " + count);
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (suppressEvents) return;

        Log.d(TAG, "onTextChanged '" + s + "' - " + start + " - " + before + " - " + count);
        if (count > before) {
          CharSequence added = s.subSequence(start + before, start + count);
          Log.d(TAG, "Text added '" + added + "'");
          onTextReceived(added.toString());
        } else if (count == before) {
          CharSequence replaced = s.subSequence(start, start + count);
          Log.d(TAG, "Text replaced '" + replaced + "'");
          onTextReceived(replaced.toString());
        } else {
          Log.d(TAG, "Text deleted");
          onTextDeleted();
        }
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });
    editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
      @Override
      public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (suppressEvents) return false;

        Log.d(TAG, "onEditorAction " + actionId + " - " + event);

        onKeyReceived(null, KeyEvent.KEYCODE_ENTER);

        return false;
      }
    });
    editText.setOnKeyListener(new View.OnKeyListener() {
      @Override
      public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (suppressEvents) return false;

        Log.d(TAG, "onKey" + event);

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
          onKeyReceived("" + event.getDisplayLabel(), event.getKeyCode());
        }
        return false;
      }
    });

    new AsyncTask<Void,Void,Boolean>() {
      @Override
      protected Boolean doInBackground(Void... params) {
        return Shell.SU.available();
      }

      @Override
      protected void onPostExecute(Boolean suAvailable) {
        if (suAvailable) {
          logLine(Html.fromHtml("<b>Su is available, running test</b><br/>Don't touch the screen!"));

          buildGeometry();
        } else {
          logLine(Html.fromHtml("<b>Su not available</b><br/>Please run this on a rooted device and allow root for the app"));
        }
      }
    }.execute();

    touchView = new TouchView(this);
    touchView.setKeyboardGeometry(this);
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.FILL_HORIZONTAL | Gravity.FILL_VERTICAL;
    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    wm.addView(touchView, params);

    currentKeyboard = Settings.Secure.getString(getContentResolver(),
        Settings.Secure.DEFAULT_INPUT_METHOD);

    if (keyboards == null) {
      if (KeyboardSwitcher.isAccessibilityServiceEnabled(this)) {
        keyboards = KeyboardSwitcher.getKeyboards(this);
        Log.i(TAG, "Keyboards " + keyboards);
        logLine("Preparing to test " + keyboards.size() + " keyboards: " + keyboards.toString());
      } else {
        keyboards = Collections.emptyList();
        logTitle("KeyboardSwitcher accessibility service not enabled");
        logLine("Only testing the current keyboard");
      }
    }

    keyboards.remove(currentKeyboard);

    logLine("Testing keyboard " + currentKeyboard + " (" + keyboards.size() + " remaining)");
  }

  @Override
  protected void onPause() {
    super.onPause();

    if (running) {
      startActivity(new Intent().setClassName(getPackageName(), this.getClass().getName())
          .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_EXCLUDE_STOPPED_PACKAGES));
    }
  }

  @Override
  public void onBackPressed() {
    stopScenario();
  }

  public void stopScenario() {
    if (running) {
      logTitle("Stopping run, press again to exit");
      currentCommand = null;
      running = false;
    } else {
      super.onBackPressed();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (shellCommandRunner != null) {
      shellCommandRunner.shutdown();
      shellCommandRunner = null;
    }

    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    wm.removeViewImmediate(touchView);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.cancel_action:
        stopScenario();
        return true;

      default:
        return super.onOptionsItemSelected(item);
    }
  }

  private void buildGeometry() {
    shellCommandRunner = new ShellCommandRunner(new Shell.Builder().useSU().open(new Shell.OnCommandResultListener() {
      @Override
      public void onCommandResult(int i, int i1, List<String> list) {
        Log.d(TAG, i + " - " + i1 + " - " + list.toString());
      }
    }));

    handler.postDelayed(new Runnable() {
      public void run() {
        Rect r = new Rect();
        editText.getGlobalVisibleRect(r);
        // stimulate stubborn keyboards into popping up
        addCommand(new TapCommand(KeyboardGeometry.this, r.centerX(), r.centerY()) {
          @Override
          public void onDone(boolean success) {
            //noinspection PointlessBooleanExpression
            if (!SHORT_SCENARIO) {
              new AutomaticScenario(KeyboardGeometry.this);
            } else {
              addKey(10, 10, "a");
              onScenarioDone(true);
            }
          }
        });
      }
    }, 1000);
  }

  public void onScenarioDone(boolean success) {
    logLine("onScenarioDone " + success);
    running = false;
    Log.i(TAG, foundKeys.values().toString());

    if (success) {
      logLine("Uploading " + foundKeys.size() + " keys");
      new AsyncTask<Void, Void, Exception>() {
        int retries = 3;

        @Override
        protected Exception doInBackground(Void... nothing) {
          try {
            KeyboardGeometryUploader.uploadSession(KeyboardGeometry.this, foundKeys.values());
            return null;
          } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);

            if (retries-- >= 0) {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException ignored) {
              }

              Log.d(TAG, "Retrying", e);
              publishProgress();
              return doInBackground(nothing);
            } else {
              return e;
            }
          }
        }

        @Override
        protected void onProgressUpdate(Void... nothing) {
          logLine("Retrying upload");
        }

        @Override
        protected void onPostExecute(Exception e) {
          if (e == null) {
            logLine("Upload complete");
              nextKeyboard();
          } else {
            logTitle("The upload failed");
            logLine(e.toString());
            logLine("Make sure you have an internet connection and run the scenario again");
          }
        }
      }.execute((Void[]) null);
    } else {
      logLine("Skipping result upload");
      nextKeyboard();
    }
  }

  private void nextKeyboard() {
    if (keyboards == null || keyboards.size() == 0) {
      keyboards = null;
      logTitle("You can quit now");
    } else {
      logTitle("Switching to next keyboard");
      KeyboardSwitcher.switchKeyboard(this, keyboards.iterator().next(), new Runnable() {
        @Override
        public void run() {
          if (!isFinishing()) {
            finish();
            startActivity(new Intent(KeyboardGeometry.this, KeyboardGeometry.class));
          }
        }
      });
    }
  }

  public synchronized void addCommand(final TouchCommand command) {
    if (!running) {
      return;
    }

    if (currentCommand != null) {
      throw new IllegalStateException("Trying to run two commands");
    }

    currentCommand = command;

    handler.removeCallbacks(eventFailed);
    if (currentCommand instanceof TapCommand) {
      touchView.drawTouch(((TapCommand) currentCommand).x, ((TapCommand) currentCommand).y);
    }

    // if a touch triggers more than one event (KeyEvent and EditorAction), let both events be handled
    // before sending the next touch (the second one will be suppressed anyway)
    handler.postDelayed(commandAdder, 500);
  }

  public void onTextReceived(String added) {
    eventReceived();

    if (currentCommand != null) {
      TouchCommand oldCommand = currentCommand;
      currentCommand = null;
      oldCommand.setTextReceived(added);
    } else {
      Log.w(TAG, "onTextReceived no currentCommand");
    }
  }

  public void onKeyReceived(String textReceived, int keyCode) {
    eventReceived();

    if (currentCommand != null) {
      TouchCommand oldCommand = currentCommand;
      currentCommand = null;
      oldCommand.setKeyReceived(textReceived, keyCode);
    } else {
      Log.w(TAG, "onKeyReceived no currentCommand");
    }
  }

  private void onTextDeleted() {
    eventReceived();

    if (currentCommand != null) {
      TouchCommand oldCommand = currentCommand;
      currentCommand = null;
      oldCommand.setKeyReceived(null, KeyEvent.KEYCODE_BACK);
    } else {
      Log.w(TAG, "onTextDeleted no currentCommand");
    }
  }

  public void eventReceived() {
    handler.removeCallbacks(eventFailed);
    suppressEvents = true;
  }

  Runnable eventFailed = new Runnable() {
    @Override
    public void run() {
      Log.d(TAG, "event timeout");
      if (currentCommand != null) {
        TouchCommand oldCommand = currentCommand;
        currentCommand = null;
        oldCommand.onNothingReceived();
      } else {
        Log.w(TAG, "eventFailed no currentCommand");
      }
    }
  };

  Runnable scrollBottomRunnable = new Runnable() {
    public void run() {
      scrollView.fullScroll(View.FOCUS_DOWN);
    }
  };

  Runnable commandAdder = new Runnable() {
    public void run() {
      try {
        suppressEvents = false;
        Log.d(TAG, "Enabling events");
        if (currentCommand != null) {
          shellCommandRunner.addCommand(currentCommand.getShellCommand());
        } else {
          Log.w(TAG, "commandAdder no currentCommand");
        }
      } catch (Exception e) {
        Log.e(TAG, "Concurrent commands", e);
        logLine("Please don't interfere with the test ;-)");
      }
      handler.postDelayed(eventFailed, EVENT_DELAY);
    }
  };

  public boolean addKey(int x, int y, String character) {
    return addKeyInfo(KeyInfo.getCharacterAt(x, y, character));
  }

  public boolean addSpecial(int x, int y, int keyCode) {
    return addKeyInfo(KeyInfo.getSpecialAt(x, y, keyCode));
  }

  public boolean addCompletion(int x, int y) {
    return addKeyInfo(KeyInfo.getCompletionAt(x, y));
  }

  public boolean addKeyInfo(KeyInfo newKey) {
    KeyInfo existingKey = foundKeys.get(newKey.getKey());

    if (existingKey == null) {
      foundKeys.put(newKey.getKey(), newKey);
      logLine("Found " + newKey.getKey().description());
      return true;
    } else {
      existingKey.averageLocationWith(newKey);
      Log.d(TAG, "Moving " + existingKey.getKey().description());
      return false;
    }
  }

  public void logLine(CharSequence line) {
    Log.i(TAG, line.toString());
    logView.append("\n");
    logView.append(line);
    handler.post(scrollBottomRunnable);
  }

  public void logTitle(String title) {
    Log.d(TAG, "###");
    logLine(Html.fromHtml("<b>" + title + "</b>"));
  }

  @Override
  public void onSoftKeyboardStateChanged(boolean open) {
    keyboardTop = screenSize.y - keyboardStateHelper.getmLastSoftKeyboardHeightInPx();
    keyboardStateHelper.dispose();

    logLine("Top of keyboard: " + keyboardTop);
  }

  public void clearText() {
    boolean wasSuppressed = suppressEvents;
    suppressEvents = true;
    editText.setText("");
    suppressEvents = wasSuppressed;
  }

  public String getText() {
    return editText.getText().toString();
  }
}
