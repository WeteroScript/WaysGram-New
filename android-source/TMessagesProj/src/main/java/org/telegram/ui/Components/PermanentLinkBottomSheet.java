package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.ManageLinksActivity;

import java.util.ArrayList;

public class PermanentLinkBottomSheet extends BottomSheet {

    private final TextView titleView;
    private final TextView subtitle;
    private final TextView manage;
    private final RLottieImageView imageView;
    private final RLottieDrawable linkIcon;
    private final LinkActionView linkActionView;
    private final long chatId;
    private final boolean isChannel;
    private final TLRPC.ChatFull chatInfoAtOpen;
    private BaseFragment fragment;

    public PermanentLinkBottomSheet(Context context, boolean needFocus, BaseFragment fragment, TLRPC.ChatFull info, long chatId, boolean isChannel) {
        super(context, needFocus);
        this.chatId = chatId;
        this.isChannel = isChannel;
        this.chatInfoAtOpen = info;
        this.fragment = fragment;

        setAllowNestedScroll(true);
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.addView(linearLayout);

        ImageView closeView = new ImageView(context);
        closeView.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
        closeView.setColorFilter(getThemedColor(Theme.key_sheet_other));
        closeView.setImageResource(R.drawable.ic_layer_close);
        closeView.setOnClickListener((view) -> dismiss());
        int closeViewPadding = AndroidUtilities.dp(8);
        closeView.setPadding(closeViewPadding, closeViewPadding, closeViewPadding, closeViewPadding);
        frameLayout.addView(closeView, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.END, 6, 8, 8, 0));

        linkActionView = new LinkActionView(context, fragment, this, chatId, true, isChannel);
        linkActionView.setPermanent(true);
        imageView = new RLottieImageView(context);
        linkIcon = new RLottieDrawable(R.raw.shared_link_enter, AndroidUtilities.dp(90), AndroidUtilities.dp(90), false, null);
        linkIcon.setCustomEndFrame(42);
        imageView.setAnimation(linkIcon);
        linkActionView.setUsers(0, null);
        linkActionView.hideRevokeOption(true);
        linkActionView.setDelegate(() -> generateLink(true));

        titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.InviteLink));
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        subtitle = new TextView(context);
        subtitle.setText(isChannel ? LocaleController.getString(R.string.LinkInfoChannel) : LocaleController.getString(R.string.LinkInfo));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        subtitle.setLineSpacing(subtitle.getLineSpacingExtra(), subtitle.getLineSpacingMultiplier() * 1.1f);

        manage = new TextView(context);
        manage.setText(LocaleController.getString(R.string.ManageInviteLinks));
        manage.setGravity(Gravity.CENTER);
        manage.setEllipsize(TextUtils.TruncateAt.END);
        manage.setSingleLine(true);
        manage.setTypeface(AndroidUtilities.bold());
        manage.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        manage.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        manage.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), Color.TRANSPARENT, ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 120)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            manage.setLetterSpacing(0.025f);
        }
        manage.setOnClickListener(view -> {
            if (info == null) return;
            ManageLinksActivity manageFragment = new ManageLinksActivity(info.id, 0, 0);
            manageFragment.setInfo(info, info.exported_invite);
            fragment.presentFragment(manageFragment);
            dismiss();
        });

        linearLayout.addView(imageView, LayoutHelper.createLinear(90, 90, Gravity.CENTER_HORIZONTAL, 0, 33, 0, 0));
        linearLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 60, 10, 60, 0));
        linearLayout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 28, 7, 28, 2));
        linearLayout.addView(linkActionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        linearLayout.addView(manage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 14, -2, 14, 6));

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(frameLayout);

        setCustomView(scrollView);

        TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(chatId);
        if (!waysGramGroup() && chat != null && ChatObject.isPublic(chat)) {
            linkActionView.setLink("https://" + org.telegram.messenger.MessagesController.getInstance(currentAccount).linkPrefix + "/" + ChatObject.getPublicUsername(chat));
            manage.setVisibility(View.GONE);
        } else if (info != null && info.exported_invite != null) {
            linkActionView.setLink(info.exported_invite.link);
        } else {
            generateLink(false);
        }

        updateColors();
    }

    private boolean waysGramGroup() {
        return org.telegram.tgnet.WaysGramTransport.ENABLED && !isChannel;
    }

    boolean linkGenerating;
    TLRPC.TL_chatInviteExported invite;

    private void generateLink(boolean showDialog) {
        if (linkGenerating) {
            return;
        }
        linkGenerating = true;
        if (waysGramGroup() && !showDialog) {
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                TLRPC.TL_chatInviteExported loaded = null;
                try {
                    loaded = org.telegram.tgnet.WaysGramInvites.groupPermanentInvite(currentAccount, chatId,
                            UserConfig.getInstance(currentAccount).getClientUserId(), false);
                } catch (org.telegram.tgnet.WaysGramApi.ApiException e) {
                    org.telegram.messenger.FileLog.e(e);
                }
                final TLRPC.TL_chatInviteExported result = loaded;
                AndroidUtilities.runOnUIThread(() -> {
                    linkGenerating = false;
                    if (result == null) {
                        if (isShowing()) android.widget.Toast.makeText(getContext(), "Не удалось загрузить ссылку-приглашение", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    invite = result;
                    TLRPC.ChatFull cached = MessagesController.getInstance(currentAccount).getChatFull(chatId);
                    if (cached != null) cached.exported_invite = result;
                    if (chatInfoAtOpen != null) chatInfoAtOpen.exported_invite = result;
                    linkActionView.setLink(result.link);
                });
            });
            return;
        }
        TLRPC.TL_messages_exportChatInvite req = new TLRPC.TL_messages_exportChatInvite();
        req.legacy_revoke_permanent = true;
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(-chatId);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null) {
                invite = (TLRPC.TL_chatInviteExported) response;

                TLRPC.ChatFull chatInfo = MessagesController.getInstance(currentAccount).getChatFull(chatId);
                if (chatInfo != null) {
                    chatInfo.exported_invite = invite;
                }

                linkActionView.setLink(invite.link);

                if (showDialog && fragment != null) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                    builder.setMessage(LocaleController.getString(R.string.RevokeAlertNewLink));
                    builder.setTitle(LocaleController.getString(R.string.RevokeLink));
                    builder.setNegativeButton(LocaleController.getString(R.string.OK), null);
                    fragment.showDialog(builder.create());
                }
            }
            linkGenerating = false;
        }));
    }

    @SuppressWarnings("Convert2MethodRef")
    @Override
    public void show() {
        super.show();
        AndroidUtilities.runOnUIThread(() -> linkIcon.start(), 50);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate descriptionDelegate = this::updateColors;
        arrayList.add(new ThemeDescription(titleView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        arrayList.add(new ThemeDescription(subtitle, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_dialogTextBlack));
        arrayList.add(new ThemeDescription(manage, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_featuredStickers_addButton));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_featuredStickers_addButton));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_featuredStickers_buttonText));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_windowBackgroundWhiteBlueText));
        return arrayList;
    }

    private void updateColors() {
        imageView.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(90), Theme.getColor(Theme.key_featuredStickers_addButton)));
        manage.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), Color.TRANSPARENT, ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 120)));
        int color = Theme.getColor(Theme.key_featuredStickers_buttonText);
        linkIcon.setLayerColor("Top", color);
        linkIcon.setLayerColor("Bottom", color);
        linkIcon.setLayerColor("Center", color);
        linkActionView.updateColors();
        setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
    }

    @Override
    public void dismiss() {
        super.dismiss();
    }
}
