package org.telegram.tgnet;

import android.net.Uri;
import org.telegram.messenger.BuildVars;

/** Public link branding is separate from the API transport origin. */
public final class WaysGramLinks {
    public static final String HOST = "t.wg";
    public static final String BASE = "https://" + HOST;
    private WaysGramLinks() {}

    public static boolean isHost(String host) { return HOST.equalsIgnoreCase(host); }

    private static boolean isApiHost(String host) {
        return host != null && host.equalsIgnoreCase(Uri.parse(BuildVars.WAYSGRAM_API_BASE_URL).getHost());
    }

    public static boolean isShareRoute(Uri uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null) return false;
        String host = uri.getHost();
        if (isHost(host)) {
            // https://t.wg/<username> or https://t.wg/<short-invite-code>
            return uri.getPath().matches("/[A-Za-z][A-Za-z0-9_]{1,31}")
                    || uri.getPath().matches("/[A-Za-z0-9_-]{20,128}");
        }
        if (!isApiHost(host)) return false;
        return uri.getPath().matches("/api/(groups|channels)/invites/[A-Za-z0-9_-]{16,128}")
                || uri.getPath().matches("/api/channel-subscriptions/[A-Za-z0-9_-]{43}");
    }

    public static String canonicalShareLink(String link) {
        if (!WaysGramTransport.ENABLED || link == null) return link;
        Uri uri = Uri.parse(link);
        String path = uri.getPath();
        if (isHost(uri.getHost()) && path != null) {
            String segment = path.startsWith("/") ? path.substring(1) : path;
            if (segment.matches("[A-Za-z][A-Za-z0-9_]{1,31}|[A-Za-z0-9_-]{20,128}")) {
                return BASE + "/" + segment;
            }
        }
        if (isApiHost(uri.getHost()) && path != null) {
            java.util.Matcher matcher = java.util.regex.Pattern
                    .compile("/api/(?:groups|channels)/invites/([A-Za-z0-9_-]{20,128})")
                    .matcher(path);
            if (matcher.matches()) {
                return BASE + "/" + matcher.group(1);
            }
        }
        return isShareRoute(uri) ? BASE + uri.getPath() : link;
    }
}
