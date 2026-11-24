package org.telegram.tgnet;

import java.util.ArrayList;

import org.telegram.messenger.MessageObject;

/**
 * Реакции WaysGram: палитра, каталог и документы для отрисовки.
 *
 * <p>{@link #EMOJI} — общая палитра для выбора реакции (меню сообщения и шторка).
 * {@link #contains(String)} — фильтр для реакций, пришедших с сервера.
 *
 * <p>Клиент узнаёт, какие реакции вообще существуют, из
 * {@code messages.getAvailableReactions}. Пока методы не обрабатывались, список
 * {@code MediaDataController.reactionsList} был пуст: в каналах не открывался
 * выбор реакции, а у уже поставленных реакций не было ни текстуры, ни анимации
 * (рисовать было нечего, кроме системного шрифта). Здесь каталог собирается
 * локально из TGS-ассетов, которые отдаёт {@link WaysGramAnimatedEmoji}, поэтому
 * {@code upload.getFile} обслуживает их прямо из APK.
 *
 * <p>Слоты каталога делятся на два набора ассетов. Иконки и превью
 * ({@code static_icon}, {@code center_icon}, {@code appear/select/activate_animation}) —
 * рисунок эмодзи: клиент рисует его последний кадр в чипе реакции и палитре.
 * Эффекты нажатия ({@code effect_animation}, {@code around_animation}) — конфетти
 * и искры этого эмодзи: они проигрываются, когда реакцию ставят.
 */
public final class WaysGramReactions {
    /** Стабильный хеш каталога: клиент кэширует список и просит notModified. */
    private static final int REACTIONS_HASH = 0x5757_0001;
    private static final long TOP_HASH = 0x5757_0002L;
    private static final long SAVED_HASH = 0x5757_0003L;
    private static final int TOP_LIMIT = 8;

    /** Пары «эмодзи, название». Порядок — как в Telegram, затем популярные добавки. */
    private static final String[][] REACTIONS = {
            {"👍", "Thumbs Up"},
            {"❤️", "Heart"},
            {"🔥", "Fire"},
            {"🥰", "Smiling Face with Hearts"},
            {"👏", "Clapping Hands"},
            {"😁", "Beaming Face"},
            {"🤔", "Thinking Face"},
            {"🤯", "Exploding Head"},
            {"😱", "Face Screaming in Fear"},
            {"🎉", "Party Popper"},
            {"🤩", "Star-Struck"},
            {"😍", "Smiling Face with Heart-Eyes"},
            {"😘", "Face Blowing a Kiss"},
            {"😂", "Face with Tears of Joy"},
            {"😢", "Crying Face"},
            {"🙏", "Folded Hands"},
            {"👌", "OK Hand"},
            {"🕊️", "Dove"},
            {"🤝", "Handshake"},
            {"💔", "Broken Heart"},
            {"🥱", "Yawning Face"},
            {"🥴", "Woozy Face"},
            {"😴", "Sleeping Face"},
            {"😭", "Loudly Crying Face"},
            {"👎", "Thumbs Down"},
            {"💯", "Hundred Points"},
            {"🤣", "Rolling on the Floor Laughing"},
            {"😎", "Smiling Face with Sunglasses"},
            {"🤡", "Clown Face"},
            {"🦄", "Unicorn"},
            {"🍾", "Bottle with Popping Cork"},
            {"💊", "Pill"},
            {"🌭", "Hot Dog"},
            {"🍓", "Strawberry"},
            {"🎃", "Jack-O-Lantern"},
            {"🎊", "Confetti Ball"},
            {"🏆", "Trophy"},
            {"⚡", "High Voltage"},
            {"✨", "Sparkles"},
            {"💩", "Pile of Poo"},
            {"❤️‍🔥", "Heart on Fire"},
            {"😮", "Face with Open Mouth"},
            {"🤬", "Face with Symbols on Mouth"},
            {"😡", "Pouting Face"},
            {"🤮", "Face Vomiting"},
            {"👀", "Eyes"},
            {"🙌", "Raising Hands"},
            {"🥳", "Partying Face"},
            {"🤗", "Smiling Face with Hugging Hands"},
            {"🫡", "Saluting Face"},
            {"🎄", "Christmas Tree"},
    };

    /** Палитра для выбора реакции — единый список эмодзи для меню и шторки. */
    public static final String[] EMOJI = new String[REACTIONS.length];

    static {
        for (int i = 0; i < REACTIONS.length; i++) {
            EMOJI[i] = REACTIONS[i][0];
        }
    }

    private static ArrayList<TLRPC.TL_availableReaction> cached;

    private WaysGramReactions() {
    }

    /**
     * Пускаем реакцию в отрисовку, если для неё есть локальная анимация или она
     * входит в палитру. U+FE0F и оттенки кожи не влияют на результат.
     */
    public static boolean contains(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String item : EMOJI) {
            if (normalize(item).equals(normalized)) {
                return true;
            }
        }
        return WaysGramAnimatedEmoji.has(normalized) || WaysGramReactionAssets.indexOf(normalized) >= 0;
    }

    /** Сравнение эмодзи без U+FE0F и модификаторов оттенка кожи. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\uFE0F') {
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < value.length()) {
                int code = Character.toCodePoint(c, value.charAt(i + 1));
                if (code >= 0x1F3FB && code <= 0x1F3FF) {
                    i++;
                    continue;
                }
                out.append(c).append(value.charAt(i + 1));
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static boolean handles(TLObject request) {
        return request instanceof TLRPC.TL_messages_getAvailableReactions
                || request instanceof TLRPC.TL_messages_getTopReactions
                || request instanceof TLRPC.TL_messages_getRecentReactions
                || request instanceof TLRPC.TL_messages_getDefaultTagReactions;
    }

    public static TLObject handle(TLObject request) {
        if (request instanceof TLRPC.TL_messages_getAvailableReactions) {
            if (((TLRPC.TL_messages_getAvailableReactions) request).hash == REACTIONS_HASH) {
                return new TLRPC.TL_messages_availableReactionsNotModified();
            }
            TLRPC.TL_messages_availableReactions result = new TLRPC.TL_messages_availableReactions();
            result.hash = REACTIONS_HASH;
            result.reactions = catalog();
            return result;
        } else if (request instanceof TLRPC.TL_messages_getTopReactions) {
            if (((TLRPC.TL_messages_getTopReactions) request).hash == TOP_HASH) {
                return new TLRPC.TL_messages_reactionsNotModified();
            }
            return reactions(top(), TOP_HASH);
        } else if (request instanceof TLRPC.TL_messages_getDefaultTagReactions) {
            if (((TLRPC.TL_messages_getDefaultTagReactions) request).hash == SAVED_HASH) {
                return new TLRPC.TL_messages_reactionsNotModified();
            }
            return reactions(savedTags(), SAVED_HASH);
        } else if (request instanceof TLRPC.TL_messages_getRecentReactions) {
            // Истории реакций у WaysGram-сервера нет — не затираем локальный кеш клиента.
            return new TLRPC.TL_messages_reactionsNotModified();
        }
        return null;
    }

    private static TLRPC.TL_messages_reactions reactions(ArrayList<TLRPC.Reaction> list, long hash) {
        TLRPC.TL_messages_reactions result = new TLRPC.TL_messages_reactions();
        result.hash = hash;
        result.reactions = list;
        return result;
    }

    private static ArrayList<TLRPC.Reaction> emojis(ArrayList<TLRPC.TL_availableReaction> all, int limit) {
        ArrayList<TLRPC.Reaction> list = new ArrayList<>();
        for (int i = 0; i < all.size() && list.size() < limit; i++) {
            if (all.get(i).inactive) {
                continue;
            }
            TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
            reaction.emoticon = all.get(i).reaction;
            list.add(reaction);
        }
        return list;
    }

    private static ArrayList<TLRPC.Reaction> top() {
        return emojis(catalog(), TOP_LIMIT);
    }

    private static ArrayList<TLRPC.Reaction> savedTags() {
        return emojis(catalog(), Integer.MAX_VALUE);
    }

    /**
     * Каталог с документами. Для каждой реакции добавляется неактивный дубль без
     * U+FE0F: сервер может вернуть эмодзи в любой форме, а чип реакции ищется по
     * точному совпадению строки с картой каталога.
     */
    public static synchronized ArrayList<TLRPC.TL_availableReaction> catalog() {
        if (cached != null) {
            return cached;
        }
        if (WaysGramReactionAssets.count() > 0) {
            ArrayList<TLRPC.TL_availableReaction> list = telegramCatalog();
            if (!list.isEmpty()) {
                cached = list;
                return list;
            }
        }
        ArrayList<TLRPC.TL_availableReaction> list = new ArrayList<>(REACTIONS.length * 2);
        for (String[] entry : REACTIONS) {
            TLRPC.Document art = WaysGramReactionTextures.get(entry[0]);
            if (art == null) art = WaysGramAnimatedEmoji.reaction(entry[0]);
            if (art == null) {
                continue;
            }
            list.add(available(art, entry[0], entry[1], false, list.size()));
            String alias = normalize(entry[0]);
            if (!alias.equals(entry[0])) {
                list.add(available(art, alias, entry[1], true, list.size()));
            }
        }
        cached = list;
        return list;
    }

    /**
     * Каталог из настоящих документов Telegram (выгрузка `/exportreactions`).
     *
     * <p>Идентификаторы документов телеграмные, файлы раздаёт {@link WaysGramReactionAssets}
     * через `upload.getFile`. Поэтому штатные `ReactionsEffectOverlay` и
     * `ReactionsLayoutInBubble` играют официальные анимации: `appear/select/activate` —
     * появление эмодзи, `effect_animation` — вспышка, `around_animation` — частицы.</p>
     */
    private static ArrayList<TLRPC.TL_availableReaction> telegramCatalog() {
        ArrayList<TLRPC.TL_availableReaction> list = new ArrayList<>();
        for (int i = 0; i < WaysGramReactionAssets.count(); i++) {
            TLRPC.TL_availableReaction reaction = telegramReaction(i, list.size());
            if (reaction == null) {
                continue;
            }
            list.add(reaction);
            // Сервер может вернуть эмодзи с U+FE0F: карта ищется по точной строке.
            String alias = normalize(WaysGramReactionAssets.EMOJI[i]);
            if (!alias.equals(WaysGramReactionAssets.EMOJI[i])) {
                TLRPC.TL_availableReaction copy = telegramReaction(i, list.size());
                if (copy != null) {
                    copy.reaction = alias;
                    copy.inactive = true;
                    list.add(copy);
                }
            }
        }
        return list;
    }

    private static TLRPC.TL_availableReaction telegramReaction(int index, int position) {
        TLRPC.Document icon = WaysGramReactionAssets.document(index, "static_icon");
        TLRPC.Document appear = WaysGramReactionAssets.document(index, "appear_animation");
        if (icon == null || appear == null) {
            return null;
        }
        TLRPC.TL_availableReaction reaction = new TLRPC.TL_availableReaction();
        reaction.reaction = WaysGramReactionAssets.EMOJI[index];
        reaction.title = WaysGramReactionAssets.TITLE[index];
        reaction.static_icon = icon;
        reaction.appear_animation = appear;
        reaction.select_animation = WaysGramReactionAssets.document(index, "select_animation");
        if (reaction.select_animation == null) reaction.select_animation = appear;
        reaction.activate_animation = WaysGramReactionAssets.document(index, "activate_animation");
        if (reaction.activate_animation == null) reaction.activate_animation = appear;
        reaction.effect_animation = WaysGramReactionAssets.document(index, "effect_animation");
        if (reaction.effect_animation == null) reaction.effect_animation = appear;
        reaction.around_animation = WaysGramReactionAssets.document(index, "around_animation");
        if (reaction.around_animation == null) reaction.around_animation = reaction.effect_animation;
        reaction.center_icon = WaysGramReactionAssets.document(index, "center_icon");
        if (reaction.center_icon == null) reaction.center_icon = icon;
        reaction.flags |= TLObject.FLAG_1; // around_animation + center_icon
        reaction.positionInList = position;
        return reaction;
    }

    private static TLRPC.TL_availableReaction available(TLRPC.Document art, String emoji,
                                                        String title, boolean inactive, int position) {
        // Эффекта может не быть (например, 🙌) — тогда нажатие проигрывает рисунок.
        TLRPC.Document effect = WaysGramAnimatedEmoji.reactionEffect(emoji);
        TLRPC.Document motion = effect != null ? effect : art;
        TLRPC.TL_availableReaction reaction = new TLRPC.TL_availableReaction();
        reaction.reaction = emoji;
        reaction.title = title;
        reaction.inactive = inactive;
        reaction.static_icon = art;
        reaction.appear_animation = art;
        reaction.select_animation = art;
        reaction.activate_animation = art;
        reaction.effect_animation = motion;
        reaction.around_animation = motion;
        reaction.center_icon = art;
        reaction.flags |= TLObject.FLAG_1; // around_animation + center_icon
        reaction.positionInList = position;
        return reaction;
    }

    // ------------------------------------------------------- эффект эмодзи

    /** Эмодзи, для которых эффект уже проигран в этой сессии. */
    private static final java.util.HashSet<Long> PLAYED_EFFECTS = new java.util.HashSet<>();

    /** Свежесть сообщения: эффект проигрывается только для новых, а не при прокрутке. */
    private static final int EFFECT_FRESH_SECONDS = 60;

    /** Есть ли у сообщения-эмодзи локальный эффект (конфетти, искры). */
    public static boolean hasEmojiEffect(MessageObject message) {
        return WaysGramTransport.ENABLED && emojiOf(message) != null;
    }

    /**
     * Проигрывать ли эффект при появлении сообщения: один раз на сообщение и
     * только пока оно свежее.
     */
    public static boolean consumeEmojiEffect(MessageObject message) {
        String emoji = emojiOf(message);
        if (emoji == null) {
            return false;
        }
        int date = message.messageOwner.date;
        int now = (int) (System.currentTimeMillis() / 1000);
        if (Math.abs(now - date) > EFFECT_FRESH_SECONDS) {
            return false;
        }
        long key = (message.getDialogId() << 32) ^ (message.getId() & 0xFFFFFFFFL);
        synchronized (PLAYED_EFFECTS) {
            return PLAYED_EFFECTS.add(key);
        }
    }

    /** Эмодзи сообщения, если это одиночный анимированный эмодзи с эффектом. */
    private static String emojiOf(MessageObject message) {
        if (message == null || !message.isAnimatedEmoji()) {
            return null;
        }
        String emoji = message.getStickerEmoji();
        if (emoji == null && message.messageOwner != null) {
            emoji = message.messageOwner.message;
        }
        if (emoji == null || !WaysGramAnimatedEmoji.hasEffect(emoji)) {
            return null;
        }
        return emoji;
    }
}
