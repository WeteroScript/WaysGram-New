package org.telegram.tgnet;

import android.net.Uri;
import org.telegram.messenger.BuildVars;

/** Public link branding is separate from the API transport origin. */
public final class WaysGramLinks {
    public static final String HOST = "t.wg";
    public static final String BASE = "https://" + HOST;
    private WaysGramLinks() {}

    public static boolean isHost(String host) { return HOST.equalsIgnoreCase(host); }

    public static boolean isShareRoute(Uri uri) {
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || path == null) return false;
        String host = uri.getHost();
        if (!isHost(host) && !Uri.parse(BuildVars.WAYSGRAM_API_BASE_URL).getHost().equalsIgnoreCase(host)) return false;
        return path.matches("/api/(groups|channels)/invites/[A-Za-z0-9_-]{20,128}")
                || path.matches("/api/channel-subscriptions/[A-Za-z0-9_-]{43}");
    }

    public static String canonicalShareLink(String link) {
        if (!WaysGramTransport.ENABLED || link == null) return link;
        Uri uri = Uri.parse(link);
        return isShareRoute(uri) ? BASE + uri.getPath() : link;
    }
}
