package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * Fragment — mini app: страница рынка открывается внутри приложения.
 *
 * <p>Вёрстка живёт на сервере (`/fragment`), поэтому интерфейс правится без новой сборки
 * APK. Экран держит обычный {@link WebView} и передаёт странице то, что она не может
 * узнать сама: токен сессии WaysGram и текущую тему оформления. Навигация — кнопка
 * «назад» в ActionBar и кнопка «Обновить»; внешних ссылок страница не открывает.</p>
 */
final class WaysGramFragmentActivity extends BaseFragment {

    private static final int MENU_REFRESH = 1;

    private final int account;
    private final String token;

    private FrameLayout root;
    private WebView webView;
    private LinearLayout errorView;
    private boolean pageLoaded;

    WaysGramFragmentActivity(int account) {
        setCurrentAccount(account);
        this.account = account;
        this.token = WaysGramApi.getToken(account);
    }

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    private static String text(int resource) {
        return LocaleController.getString(resource);
    }

    private String pageUrl() {
        String base = BuildVars.WAYSGRAM_API_BASE_URL;
        if (TextUtils.isEmpty(base)) {
            return "";
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/fragment";
    }

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(text(R.string.WaysGramFragmentTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    reload();
                }
            }
        });
        actionBar.createMenu().addItem(MENU_REFRESH, R.drawable.msg_retry);

        root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                && Theme.getActiveTheme().isDark()) {
            settings.setForceDark(WebSettings.FORCE_DARK_ON);
        }
        webView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        webView.addJavascriptInterface(new Bridge(), "WaysGram");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Страница рынка самодостаточна: любые внешние переходы гасим.
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                return !url.startsWith(pageUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                showError(false);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showError(true);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showError(true);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoaded = false;
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        errorView = buildErrorView(context);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(-1, -1));

        fragmentView = root;
        reload();
        return fragmentView;
    }

    private LinearLayout buildErrorView(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));
        column.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        TextView title = new TextView(context);
        title.setText(text(R.string.WaysGramFragmentOffline));
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        column.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView action = new TextView(context);
        action.setText(text(R.string.WaysGramFragmentRetry));
        action.setTextSize(15);
        action.setGravity(Gravity.CENTER);
        action.setPadding(0, dp(14), 0, 0);
        action.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        action.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        action.setOnClickListener(v -> reload());
        column.addView(action, new LinearLayout.LayoutParams(-1, -2));
        return column;
    }

    private void showError(boolean visible) {
        if (errorView == null) {
            return;
        }
        errorView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (webView != null) {
            webView.setVisibility(visible ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void reload() {
        String url = pageUrl();
        if (webView == null) {
            return;
        }
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(url)) {
            showError(true);
            return;
        }
        showError(false);
        webView.loadUrl(url);
    }

    /** То, что страница не может узнать сама: токен сессии и текущая тема. */
    private final class Bridge {
        @JavascriptInterface
        public String token() {
            return token;
        }

        @JavascriptInterface
        public String theme() {
            return Theme.getActiveTheme().isDark() ? "dark" : "light";
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        if (fragmentView != null && !pageLoaded) {
            reload();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (webView != null) {
            if (root != null) {
                root.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }
}
