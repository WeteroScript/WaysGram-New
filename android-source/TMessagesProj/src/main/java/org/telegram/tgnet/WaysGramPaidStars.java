package org.telegram.tgnet;

/**
 * Сколько звёзд списано за только что отправленное сообщение.
 *
 * <p>Ответ сервера на `messages.sendMessage` — это `TL_updateShortSentMessage`, в котором нет
 * поля для суммы оплаты: клиент применяет ответ к своему «отправляется» и теряет сумму.
 * Поэтому транспорт запоминает её по id готового сообщения, а `SendMessagesHelper` забирает
 * сумму в тот момент, когда присваивает сообщению настоящий id, и выставляет
 * `paid_message_stars`. По этому полю штатная ячейка рисует плашку «Вы оплатили ⭐N» (у
 * получателя — «получено ⭐N от un1»), как в Telegram.</p>
 */
public final class WaysGramPaidStars {
    private WaysGramPaidStars() {
    }

    private static final java.util.HashMap<String, Long> PAID = new java.util.HashMap<>();

    /**
     * Больше не нужно: запись живёт от ответа сервера до применения в интерфейсе.
     * Ограничение нужно на случай, если ответ пришёл, а сообщение в интерфейсе уже убрали.
     */
    private static final int LIMIT = 128;

    /** Запомнить сумму для сообщения с серверным id. */
    public static void remember(int account, int messageId, long stars) {
        if (messageId == 0 || stars <= 0) {
            return;
        }
        synchronized (PAID) {
            if (PAID.size() >= LIMIT) {
                PAID.clear();
            }
            PAID.put(key(account, messageId), stars);
        }
    }

    /** Сумма для сообщения; запись удаляется — второй раз она не нужна. */
    public static long take(int account, int messageId) {
        if (messageId == 0) {
            return 0;
        }
        synchronized (PAID) {
            Long stars = PAID.remove(key(account, messageId));
            return stars == null ? 0 : stars;
        }
    }

    private static String key(int account, int messageId) {
        return account + ":" + messageId;
    }
}
