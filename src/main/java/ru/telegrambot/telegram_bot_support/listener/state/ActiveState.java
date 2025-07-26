package ru.telegrambot.telegram_bot_support.listener.state;

/**
 * интерфейс активного состояния
 */
public interface ActiveState {

    /**
     * Метод установки активного состояния
     * @param chatId id чата пользователя
     * @param state наименование состояния
     */
    void setUserState(Long chatId, String state);

    /**
     * Метод предоставления состояния пользователя
     * @param chatId id чата пользователя
     * @return возвращает текущее состояние пользователя
     */
    String getUserState(Long chatId);

    /**
     * Метод очищает состояние пользователя
     * @param chatId id чата пользователя
     */
    void clearUserState(Long chatId);
}
