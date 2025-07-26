package ru.telegrambot.telegram_bot_support.listener.state.impl;

import org.springframework.stereotype.Component;
import ru.telegrambot.telegram_bot_support.listener.state.ActiveState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Компонент работы с состоянием пользователя
 */
@Component
public class UserActiveState implements ActiveState {
    private final ConcurrentHashMap<Long, String> userStates = new ConcurrentHashMap<>();

    @Override
    public void setUserState(Long chatId, String state) {
        userStates.put(chatId, state);
    }

    @Override
    public String getUserState(Long chatId) {
        return userStates.get(chatId);
    }

    @Override
    public void clearUserState(Long chatId) {
        userStates.remove(chatId);
    }
}
