package org.telegram.messenger.waysgram;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Артворк эксклюзивных моделей WaysGram, встроенный в приложение.
 *
 * <p>Анимация модели приходит с сервера так же, как любая другая анимация атрибута: клиент
 * качает её и кладёт файлом {@code <documentId>.bin} в общую папку стикеров, откуда её отдаёт
 * транспорт {@code upload.getFile}. Но у модели WaysGram синтетический отрицательный
 * идентификатор документа, которого нет в каталоге Telegram, поэтому при первом показе
 * (до первого успешного скачивания, при плохой сети или после очистки данных) ячейка подарка
 * оставалась пустой — модель просто нечего было нарисовать.</p>
 *
 * <p>Здесь та же самая анимация лежит в ресурсах приложения и один раз копируется в папку
 * стикеров. Дальше всё работает штатно: нативные ячейки подарков, карточка уникального
 * подарка и лента в профиле играют модель через обычный медиа-стек, без сети.</p>
 */
public final class WaysGramBundledArtwork {

    /** Синтетический документ модели «WaysGram» подарка Plush Pepe. */
    public static final long WAYSGRAM_MODEL_DOCUMENT_ID = -5936013938331222567L;

    /** Имя модели в traits подарка. */
    public static final String WAYSGRAM_MODEL_NAME = "WaysGram";

    /** Размер готового документа, ниже которого файл считается битым. */
    private static final long MIN_VALID_SIZE = 1024L;

    private static volatile boolean installed;

    private WaysGramBundledArtwork() {
    }

    /** Есть ли для этого документа встроенная анимация. */
    public static boolean isBundled(long documentId) {
        return documentId == WAYSGRAM_MODEL_DOCUMENT_ID;
    }

    /** Есть ли встроенная анимация для этого имени модели. */
    public static boolean isBundledName(String name) {
        return name != null && WAYSGRAM_MODEL_NAME.equalsIgnoreCase(name.trim());
    }

    /**
     * Кладёт встроенную анимацию модели в общую папку стикеров, если её там ещё нет.
     *
     * <p>Файл маленький (27 КБ) и копируется один раз за запуск: повторные вызовы стоят
     * одного чтения флага.</p>
     */
    public static void install() {
        if (installed) {
            return;
        }
        installNow();
        if (installed) {
            // Кэш документов мог запомнить неудачную сборку до появления файла.
            // Вызывается уже без блокировки класса, чтобы не пересекаться с storeSticker().
            WaysGramGiftDocuments.forget(WAYSGRAM_MODEL_DOCUMENT_ID);
        }
    }

    private static synchronized void installNow() {
        if (installed) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        final File folder = WaysGramGiftDocuments.stickerFolder();
        if (folder == null) {
            return;
        }
        // Не через stickerFile(): тот сам вызывает install(), а рекурсия здесь недопустима.
        final File target = new File(folder, WAYSGRAM_MODEL_DOCUMENT_ID + ".bin");
        if (target.getParentFile() == null) {
            return;
        }
        if (target.isFile() && target.length() >= MIN_VALID_SIZE) {
            installed = true;
            return;
        }
        final File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream input = context.getResources().openRawResource(R.raw.waysgram_model);
             FileOutputStream output = new FileOutputStream(temporary)) {
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (Exception exception) {
            FileLog.e(exception);
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return;
        }
        if (temporary.length() < MIN_VALID_SIZE || !temporary.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return;
        }
        installed = true;
    }

    /** Готовый документ модели; {@code null}, если анимацию не удалось подготовить. */
    public static TLRPC.TL_document document() {
        install();
        final File folder = WaysGramGiftDocuments.stickerFolder();
        if (folder == null) {
            return null;
        }
        return WaysGramGiftDocuments.buildDocument(
                WAYSGRAM_MODEL_DOCUMENT_ID,
                new File(folder, WAYSGRAM_MODEL_DOCUMENT_ID + ".bin"),
                ""
        );
    }
}
