package app.herd.dev;

import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Chrome remote debugging (chrome://inspect) for the WebView — debug builds only,
        // per task item 1. BuildConfig.DEBUG is generated per build type by Gradle, so this
        // is compiled out (false) in a release build automatically.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }
}
