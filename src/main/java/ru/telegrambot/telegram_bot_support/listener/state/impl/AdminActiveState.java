package ru.telegrambot.telegram_bot_support.listener.state.impl;

import org.springframework.stereotype.Component;
import ru.telegrambot.telegram_bot_support.listener.state.ActiveState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Компонент работы с состоянием администратора
 */
@Component
public class AdminActiveState implements ActiveState {
    private final ConcurrentHashMap<Long, String> adminStates = new ConcurrentHashMap<>();

    @Override
    public void setUserState(Long chatId, String state) {
        adminStates.put(chatId, state);
    }

    @Override
    public String getUserState(Long chatId) {
        return adminStates.get(chatId);
    }

    @Override
    public void clearUserState(Long chatId) {
        adminStates.remove(chatId);
    }
}
