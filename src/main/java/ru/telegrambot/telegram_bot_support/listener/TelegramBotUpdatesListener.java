package ru.telegrambot.telegram_bot_support.listener;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.TelegramException;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.BaseResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.telegrambot.telegram_bot_support.listener.service.*;
import ru.telegrambot.telegram_bot_support.listener.service.message.PreparerMessageService;
import ru.telegrambot.telegram_bot_support.listener.service.message.SenderMessageService;
import ru.telegrambot.telegram_bot_support.listener.state.impl.AdminActiveState;
import ru.telegrambot.telegram_bot_support.listener.state.impl.UserActiveState;

import java.util.List;


import static ru.telegrambot.telegram_bot_support.constant.InformationConstant.*;

/**
 * Главный сервис -
 * - отвечает за логику взаимодействия пользователя/администратора с телеграм ботом
 */
@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    public static long chatIdNewUser = 0L;

    private final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);

    private final TelegramBot telegramBot;
    private final PreparerMessageService preparerMessageService;
    private final SenderMessageService senderMessageService;
    private final ForwarderPhotoToVerifyService forwarderPhotoToVerifyService;
    private final UserActiveState userActiveState;
    private final AdminActiveState adminActiveState;
    private final AddingUserToDataBaseService addingUserToDataBaseService;
    private final InlineButtonService inlineButtonService;

    public TelegramBotUpdatesListener(TelegramBot telegramBot, PreparerMessageService preparerMessageService, SenderMessageService senderMessageService, ForwarderPhotoToVerifyService forwarderPhotoToVerifyService, UserActiveState userActiveState, AdminActiveState adminActiveState, AddingUserToDataBaseService addingUserToDataBaseService, InlineButtonService inlineButtonService) {
        this.telegramBot = telegramBot;
        this.preparerMessageService = preparerMessageService;
        this.senderMessageService = senderMessageService;
        this.forwarderPhotoToVerifyService = forwarderPhotoToVerifyService;
        this.userActiveState = userActiveState;
        this.adminActiveState = adminActiveState;
        this.addingUserToDataBaseService = addingUserToDataBaseService;
        this.inlineButtonService = inlineButtonService;
    }

    // сетап бота и кнопок
    @PostConstruct
    public void init() {
        telegramBot.setUpdatesListener(this);
        try {
            setCommands();
        } catch (TelegramException e) {
            logger.error(e.getMessage());
        }
    }

    /**
     * метод обработки запросов (updates)
     * @param updates available updates
     * @return int UpdatesListener.CONFIRMED_UPDATES_ALL
     */
    @Override
    public int process(List<Update> updates) {
        updates.forEach(update -> {
            logger.info("Processing update {}", update);

            if (update.callbackQuery() != null) {
                handleCallbackQuery(update);
                return;
            }

            if (update.message() != null) {
                handleMessage(update);
            }

        });

        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void handleMessage(Update update) {
        Message messageChat = update.message();

        // часть кода, отвечающая за пересылку сообщения с фотографией(чек оплаты)
        // от пользователя к администратору для дальнейшей проверки оригинальности фотографии
        if (messageChat.photo() != null && userActiveState.getUserState(update.message().chat().id()) != null) {
            ForwardMessage forwardMessage = forwarderPhotoToVerifyService.forwardMessageToAdmin(
                    update.message().chat().id(),
                    update.message().messageId());
            senderMessageService.sendForwardMessage(forwardMessage);

            SendMessage textMessageToAdminForCheckingPayment =
                    preparerMessageService.getTextMessageToAdminForCheckingPayment(
                            update.message().chat().id(),
                            update.message().chat().firstName(),
                            update.message().from().username());
            senderMessageService.sendMessage(textMessageToAdminForCheckingPayment);

            SendMessage sendTextMessageToUserAboutGettingPhoto =
                    preparerMessageService.getSendTextMessageToUserAboutGettingPhoto(
                            update.message().chat().id());
            senderMessageService.sendMessage(sendTextMessageToUserAboutGettingPhoto);

            adminActiveState.setUserState(ADMIN_CHAT_ID, "AWAITING_NAME");

            chatIdNewUser = update.message().chat().id();
            return;
        }

        // часть кода, отвечающая за обработку текста
        if (messageChat.text() != null) {
            String adminState = adminActiveState.getUserState(ADMIN_CHAT_ID);

            // событие для администратора для проверки фотографии от пользователя
            if (adminState != null
                    && messageChat.text().equalsIgnoreCase("да")
                    && messageChat.replyToMessage() != null
                    && !(forwarderPhotoToVerifyService.forwardMap.isEmpty())) {

                // логика параллельной обработки полученых фотографий (1 и более) для избежания потери данных
                for (Long chatId : forwarderPhotoToVerifyService.listWaiting) {

                    //добавление пользователя в БД после подтверждения оплаты + сообщение об этом
                    addingUserToDataBaseService.handleUserInput(adminState, chatId);

                    //сообщение пользователю о успешной проверки скриншота об оплате
                    telegramBot.execute(preparerMessageService.getSendTextMessageToUserAboutSuccessfulVerifying(chatId));
                    forwarderPhotoToVerifyService.forwardMap.remove(chatId);
                    forwarderPhotoToVerifyService.listWaiting.remove(chatId);
                }

                if (forwarderPhotoToVerifyService.forwardMap.isEmpty()) {
                    adminActiveState.clearUserState(ADMIN_CHAT_ID);
                }
                    return;
            }

            // событие активации бота при помощи команды /start
            if (update.message().text().equals("/start")) {

                InlineKeyboardMarkup keyboardMarkup = inlineButtonService.getButtonsForStart();

                SendPhoto startPhotoMessage = preparerMessageService
                        .getStartPhotoMessage(update.message().chat().id())
                        .replyMarkup(keyboardMarkup);

                senderMessageService.sendPhoto(startPhotoMessage);
            }
        }
    }

    // событие обрабатывающий запроса на обратный вызов
    private void handleCallbackQuery(Update update) {
        try {
            // 1. Получаем запрос обратного вызова (действие пользователя в боте)
            CallbackQuery callbackQuery = update.callbackQuery();
            if (callbackQuery == null || callbackQuery.message() == null) {
                return;
            }

            // 2. Извлекаем данные из запроса обратного вызова (действие пользователя в боте)
            String callbackData = callbackQuery.data();
            Message message = callbackQuery.message();
            long chatId = message.chat().id();
            int messageId = message.messageId();

            // 3. Создаем клавиатуру и текст
            InlineKeyboardMarkup keyboardMarkup;
            String responseText;

            // 4. Обрабатываем разные варианты обратного вызова для предоставления данных
            switch (callbackData) {
                case "list":
                    responseText = LIST_CHANNELS;
                    keyboardMarkup = inlineButtonService.getButtonsForList();
                    break;

                case "pay":
                    responseText = PAYMENT;
                    keyboardMarkup = inlineButtonService.getButtonsForPayment();
                    userActiveState.setUserState(chatId
                             , "waiting_photo");
                    break;

                case "support":
                    responseText = SUPPORT;
                    keyboardMarkup = inlineButtonService.getButtonsForSupport();
                    break;

                case "back":
                    responseText = TEXT_INITIAL;
                    keyboardMarkup = inlineButtonService.getButtonsForStart();
                    break;

                default:
                    responseText = "Неизвестная команда";
                    keyboardMarkup = null;
            }

            // 5. Создаем и отправляем обновленное сообщение (текст + кнопки)
            EditMessageCaption editMessageCaption = new EditMessageCaption(chatId, messageId).caption(responseText).parseMode(ParseMode.HTML);
            if (keyboardMarkup != null) {
                editMessageCaption.replyMarkup(keyboardMarkup);
            }

            logger.info("Sending edit for message {} in chat {}", messageId, chatId);

            BaseResponse response = telegramBot.execute(editMessageCaption);

            if (!response.isOk()) {
                logger.error("Failed to edit message: {}", response.description());
            }

        } catch (Exception e) {
            logger.error("Callback processing error", e);
        }
    }

    //сетап команд бота по умолчанию
    private void setCommands() throws TelegramException {
        BotCommand commandFirst = new BotCommand("/start", "Запуск бота");
        BotCommand commandSecond = new BotCommand("/help", "nothing");

        telegramBot.execute(new SetMyCommands(commandFirst, commandSecond));
    }
}
