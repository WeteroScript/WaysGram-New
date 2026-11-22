package org.telegram.tgnet;

/** Standard emoji shared by the local picker, mapper and transport. */
public final class WaysGramReactions {
    private WaysGramReactions() { }
    public static final String[] EMOJI = {"👍", "❤️", "😂", "😮", "😢", "🎉", "🙏", "👎", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "😡", "🤩", "😎", "💯", "❤️‍🔥", "💔", "💩", "🤮", "🤝", "👀", "🙌", "😍", "🥳", "😭", "😘", "🤣", "🤗", "🫡", "🕊️", "⚡", "🏆", "🍾", "🎄", "🤡"};
    public static boolean contains(String value) {
        if (value == null) return false;
        for (String item : EMOJI) if (item.equals(value)) return true;
        return false;
    }
}
