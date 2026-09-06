package app.exteraless.icons;

import android.util.SparseIntArray;

import org.telegram.messenger.R;

import xyz.nextalone.nagram.NaConfig;

/**
 * Встроенные наборы иконок (порт {@code com.exteragram.messenger.icons.BaseIconPacks}, 1223 строки).
 *
 * exteraGram держит три ленивых {@link SparseIntArray} «id базовой иконки → id иконки набора»:
 * {@code remix} ({@code BaseIconPacks.java:53}, {@code new SparseIntArray(590)}, 565 put),
 * {@code solar} ({@code BaseIconPacks.java:624}, 563 put) и {@code def}
 * ({@code BaseIconPacks.java:41}, 3 put). Пак выбирается в
 * {@code IconManager.getIcon(resId)} ({@code IconManager.java:1741}) — первый активный
 * базовый пак, у которого есть запись для этого ресурса.
 *
 * <p>Отличия порта от exteraGram:
 * <ul>
 *   <li>Выбор набора хранится не в {@code ExteraConfig.iconPack} (enum IconPackType), а в
 *       уже существующем ключе форка {@code NaConfig.iconReplacements}: 0 — стоковые иконки,
 *       1 — Solar, 2 — Remix. Значения 0/1 совпадают с прежней семантикой NagramX
 *       ({@code IconsResources.ICON_REPLACE_SOLAR == 1}), 2 добавлено этим портом.</li>
 *   <li>Набор {@code def} exteraGram схлопывает {@code popup_fixed_alert/2/3} в
 *       {@code popup_fixed_alert4}. У нас он перенесён, но при выборе «Default» НЕ применяется:
 *       дефолт обязан оставаться стоковым.</li>
 *   <li>{@value #SKIPPED_PUTS} подмен exteraGram пропущены — базовых drawable нет в нашем дереве
 *       Список — в комментарии ниже.</li>
 * </ul>
 */
public final class BaseIconPacks {

    /** Стоковые иконки Telegram — подмены нет. */
    public static final int BASE_DEFAULT = 0;
    /** Solar Icon Set (@Design480). */
    public static final int BASE_SOLAR = 1;
    /** Remix Icon (Remix-Design). */
    public static final int BASE_REMIX = 2;

    /**
     * Сколько put-ов exteraGram пропущено: их базовых drawable нет в нашем дереве, и
     * подмена не скомпилируется. Полный список:
     *   ai_chat, boosts, channel, extera_outline, menu_wallet, msg_bots
     *   msg_channel_14, msg_channel_filled, msg_channel_hw, msg_channel_ny, msg_data, msg_info_filled
     *   msg_photo_crop, msg_plugins, msg_rear_camera, msg_secret_14, msg_secret_hw, msg_to_beginning
     *   pencil, plugins_filled, profile_newmsg_filled, select_between, stickers_filled
     */
    static final int SKIPPED_PUTS = 23;

    private static volatile SparseIntArray solar;
    private static volatile SparseIntArray remix;
    private static volatile SparseIntArray def;

    private BaseIconPacks() {
    }

    // ---- выбор набора ----

    /** Текущий встроенный набор: {@link #BASE_DEFAULT} / {@link #BASE_SOLAR} / {@link #BASE_REMIX}. */
    public static int getSelected() {
        try {
            int value = NaConfig.INSTANCE.getIconReplacements().Int();
            return value == BASE_SOLAR || value == BASE_REMIX ? value : BASE_DEFAULT;
        } catch (Throwable t) {
            return BASE_DEFAULT;
        }
    }

    public static void setSelected(int type) {
        if (type != BASE_DEFAULT && type != BASE_SOLAR && type != BASE_REMIX) {
            type = BASE_DEFAULT;
        }
        NaConfig.INSTANCE.getIconReplacements().setConfigInt(type);
    }

    /** Название набора для экрана настроек. */
    public static String getName(int type) {
        if (type == BASE_SOLAR) {
            return "Solar Icon Set";
        }
        if (type == BASE_REMIX) {
            return "Remix Icon";
        }
        return org.telegram.messenger.LocaleController.getString(R.string.Default);
    }

    /** Автор набора ({@code BaseIconPacks.java:1192}). */
    public static String getAuthor(int type) {
        if (type == BASE_SOLAR) {
            return "@Design480";
        }
        if (type == BASE_REMIX) {
            return "Remix-Design";
        }
        return "Telegram";
    }

    /** Карта выбранного набора или null, если подмены нет. */
    public static SparseIntArray getMap(int type) {
        if (type == BASE_SOLAR) {
            return getSolar();
        }
        if (type == BASE_REMIX) {
            return getRemix();
        }
        // Набор def применяется и при «Default»: popup_fixed_alert/2/3
        // схлопываются в popup_fixed_alert4.
        return getDef();
    }

    /**
     * Порт {@code IconManager.getIcon(resId)} ({@code IconManager.java:1741}):
     * id иконки выбранного набора или сам {@code resId}, если подмены нет.
     * Дефолт (набор не выбран) обязан возвращать resId без изменений.
     */
    public static int getIcon(int resId) {
        SparseIntArray map = getMap(getSelected());
        return map == null ? resId : map.get(resId, resId);
    }

    // ---- карты ----

    public static SparseIntArray getSolar() {
        SparseIntArray value = solar;
        if (value == null) {
            synchronized (BaseIconPacks.class) {
                value = solar;
                if (value == null) {
                    solar = value = buildSolar();
                }
            }
        }
        return value;
    }

    public static SparseIntArray getRemix() {
        SparseIntArray value = remix;
        if (value == null) {
            synchronized (BaseIconPacks.class) {
                value = remix;
                if (value == null) {
                    remix = value = buildRemix();
                }
            }
        }
        return value;
    }

    public static SparseIntArray getDef() {
        SparseIntArray value = def;
        if (value == null) {
            synchronized (BaseIconPacks.class) {
                value = def;
                if (value == null) {
                    def = value = buildDef();
                }
            }
        }
        return value;
    }

    /** {@code BaseIconPacks.java:41} — применяется во всех трёх наборах. */
    private static SparseIntArray buildDef() {
        SparseIntArray map = new SparseIntArray(3);
        map.put(R.drawable.popup_fixed_alert,  R.drawable.popup_fixed_alert4);
        map.put(R.drawable.popup_fixed_alert2, R.drawable.popup_fixed_alert4);
        map.put(R.drawable.popup_fixed_alert3, R.drawable.popup_fixed_alert4);
        return map;
    }

    /** {@code BaseIconPacks.java:53} — 565 put в exteraGram, 534 здесь. */
    private static SparseIntArray buildRemix() {
        SparseIntArray map = new SparseIntArray(590);
        map.put(R.drawable.arrow_more,                R.drawable.arrow_more_remix);
        map.put(R.drawable.attach_send,               R.drawable.attach_send_remix);
        map.put(R.drawable.bot_file,                  R.drawable.msg_round_file_remix);
        map.put(R.drawable.bot_location,              R.drawable.bot_location_remix);
        map.put(R.drawable.calls_bluetooth,           R.drawable.calls_menu_bluetooth_remix);
        map.put(R.drawable.calls_camera_mini,         R.drawable.calls_camera_mini_remix);
        map.put(R.drawable.calls_decline,             R.drawable.calls_decline_remix);
        map.put(R.drawable.calls_headphones,          R.drawable.calls_menu_headset_remix);
        map.put(R.drawable.calls_menu_headset,        R.drawable.calls_menu_headset_remix);
        map.put(R.drawable.calls_menu_phone,          R.drawable.calls_menu_phone_remix);
        map.put(R.drawable.calls_mute_mini,           R.drawable.calls_mute_remix);
        map.put(R.drawable.calls_speaker,             R.drawable.calls_menu_speaker_remix);
        map.put(R.drawable.calls_unmute,              R.drawable.input_mic_pressed_remix);
        map.put(R.drawable.calls_video,               R.drawable.profile_video_remix);
        map.put(R.drawable.camera,                    R.drawable.camera_remix);
        map.put(R.drawable.camera_revert1,            R.drawable.camera_revert1_remix);
        map.put(R.drawable.camera_revert2,            R.drawable.camera_revert2_remix);
        map.put(R.drawable.chat_calls_video,          R.drawable.profile_video_remix);
        map.put(R.drawable.chat_calls_voice,          R.drawable.profile_phone_remix);
        map.put(R.drawable.chats_archive,             R.drawable.chats_archive_remix);
        map.put(R.drawable.chats_pin,                 R.drawable.msg_pin_remix);
        map.put(R.drawable.chats_replies,             R.drawable.chats_replies_remix);
        map.put(R.drawable.chats_saved,               R.drawable.chats_saved_remix);
        map.put(R.drawable.chats_unpin,               R.drawable.msg_unpin_remix);
        map.put(R.drawable.emoji_tabs_faves,          R.drawable.emoji_tabs_faves_remix);
        map.put(R.drawable.emoji_tabs_new1,           R.drawable.emoji_tabs_new1_remix);
        map.put(R.drawable.emoji_tabs_new2,           R.drawable.emoji_tabs_new2_remix);
        map.put(R.drawable.emoji_tabs_new3,           R.drawable.emoji_tabs_new3_remix);
        map.put(R.drawable.files_folder,              R.drawable.files_folder_remix);
        map.put(R.drawable.files_gallery,             R.drawable.files_gallery_remix);
        map.put(R.drawable.files_internal,            R.drawable.files_internal_remix);
        map.put(R.drawable.files_storage,             R.drawable.files_storage_remix);
        map.put(R.drawable.filled_add_photo,          R.drawable.filled_add_photo_remix);
        map.put(R.drawable.filled_button_reply,       R.drawable.msg_panel_reply_remix);
        map.put(R.drawable.filled_button_share,       R.drawable.filled_button_share_remix);
        map.put(R.drawable.filled_chatlist_mention,   R.drawable.menu_username_remix);
        map.put(R.drawable.filled_chatlist_poll,      R.drawable.filled_chatlist_poll_remix);
        map.put(R.drawable.filled_chatlist_reaction,  R.drawable.msg_reactions_filled_remix);
        map.put(R.drawable.filled_fire,               R.drawable.burn_remix);
        map.put(R.drawable.filled_forward,            R.drawable.filled_forward_remix);
        map.put(R.drawable.filled_link,               R.drawable.filled_link_remix);
        map.put(R.drawable.filled_open_message,       R.drawable.filled_open_message_remix);
        map.put(R.drawable.filled_reply_quote,        R.drawable.filled_reply_quote_remix);
        map.put(R.drawable.filled_reply_settings,     R.drawable.filled_reply_settings_remix);
        map.put(R.drawable.filter_airplane,           R.drawable.filter_airplane_remix);
        map.put(R.drawable.filter_all,                R.drawable.filter_all_remix);
        map.put(R.drawable.filter_book,               R.drawable.filter_book_remix);
        map.put(R.drawable.filter_bots,               R.drawable.filter_bots_remix);
        map.put(R.drawable.filter_cat,                R.drawable.filter_cat_remix);
        map.put(R.drawable.filter_channels,           R.drawable.filter_channels_remix);
        map.put(R.drawable.filter_crown,              R.drawable.filter_crown_remix);
        map.put(R.drawable.filter_custom,             R.drawable.filter_custom_remix);
        map.put(R.drawable.filter_favorite,           R.drawable.filter_favorite_remix);
        map.put(R.drawable.filter_flower,             R.drawable.filter_flower_remix);
        map.put(R.drawable.filter_game,               R.drawable.filter_game_remix);
        map.put(R.drawable.filter_group,              R.drawable.filter_group_remix);
        map.put(R.drawable.filter_home,               R.drawable.filter_home_remix);
        map.put(R.drawable.filter_light,              R.drawable.filter_light_remix);
        map.put(R.drawable.filter_like,               R.drawable.filter_like_remix);
        map.put(R.drawable.filter_love,               R.drawable.filter_love_remix);
        map.put(R.drawable.filter_mask,               R.drawable.filter_mask_remix);
        map.put(R.drawable.filter_money,              R.drawable.filter_money_remix);
        map.put(R.drawable.filter_note,               R.drawable.filter_note_remix);
        map.put(R.drawable.filter_palette,            R.drawable.filter_palette_remix);
        map.put(R.drawable.filter_party,              R.drawable.filter_party_remix);
        map.put(R.drawable.filter_private,            R.drawable.filter_private_remix);
        map.put(R.drawable.filter_setup,              R.drawable.filter_setup_remix);
        map.put(R.drawable.filter_sport,              R.drawable.filter_sport_remix);
        map.put(R.drawable.filter_study,              R.drawable.filter_study_remix);
        map.put(R.drawable.filter_trade,              R.drawable.filter_trade_remix);
        map.put(R.drawable.filter_travel,             R.drawable.filter_travel_remix);
        map.put(R.drawable.filter_unmuted,            R.drawable.filter_unmuted_remix);
        map.put(R.drawable.filter_unread,             R.drawable.filter_unread_remix);
        map.put(R.drawable.filter_work,               R.drawable.filter_work_remix);
        map.put(R.drawable.fingerprint,               R.drawable.fingerprint_remix);
        map.put(R.drawable.flash_auto,                R.drawable.flash_auto_remix);
        map.put(R.drawable.flash_off,                 R.drawable.flash_off_remix);
        map.put(R.drawable.flash_on,                  R.drawable.flash_on_remix);
        map.put(R.drawable.ghost,                     R.drawable.ghost_remix);
        map.put(R.drawable.group_edit,                R.drawable.group_edit_profile_remix);
        map.put(R.drawable.group_edit_profile,        R.drawable.group_edit_profile_remix);
        map.put(R.drawable.header_qr_24,              R.drawable.msg_qrcode_remix);
        map.put(R.drawable.ic_ab_back,                R.drawable.ic_ab_back_remix);
        map.put(R.drawable.ic_arrow_drop_down,        R.drawable.ic_arrow_drop_down_remix);
        map.put(R.drawable.ic_chatlist_add_2,         R.drawable.ic_chatlist_add_2_remix);
        map.put(R.drawable.ic_gallery_background,     R.drawable.ic_gallery_background_remix);
        map.put(R.drawable.ic_goinline,               R.drawable.ic_goinline_remix);
        map.put(R.drawable.ic_lock_header,            R.drawable.list_secret_remix);
        map.put(R.drawable.ic_masks_msk1,             R.drawable.ic_masks_msk1_remix);
        map.put(R.drawable.ic_outinline,              R.drawable.ic_outinline_remix);
        map.put(R.drawable.ic_send,                   R.drawable.ic_send_remix);
        map.put(R.drawable.input_attach,              R.drawable.input_attach_remix);
        map.put(R.drawable.input_bot1,                R.drawable.input_bot1_remix);
        map.put(R.drawable.input_bot2,                R.drawable.input_bot2_remix);
        map.put(R.drawable.input_calendar1,           R.drawable.input_calendar1_remix);
        map.put(R.drawable.input_calendar2,           R.drawable.input_calendar2_remix);
        map.put(R.drawable.input_forward,             R.drawable.input_forward_remix);
        map.put(R.drawable.input_gift_s,              R.drawable.msg_gift_premium_remix);
        map.put(R.drawable.input_keyboard,            R.drawable.input_keyboard_remix);
        map.put(R.drawable.input_message,             R.drawable.msg_discussion_remix);
        map.put(R.drawable.input_mic,                 R.drawable.input_mic_remix);
        map.put(R.drawable.input_mic_pressed,         R.drawable.input_mic_pressed_remix);
        map.put(R.drawable.input_notify_off,          R.drawable.msg_bell_mute_remix);
        map.put(R.drawable.input_notify_on,           R.drawable.msg_notifications_remix);
        map.put(R.drawable.input_reply,               R.drawable.input_reply_remix);
        map.put(R.drawable.input_schedule,            R.drawable.input_schedule_remix);
        map.put(R.drawable.input_smile,               R.drawable.input_smile_remix);
        map.put(R.drawable.input_suggest_paid_24,     R.drawable.input_suggest_paid_remix);
        map.put(R.drawable.input_video,               R.drawable.input_video_remix);
        map.put(R.drawable.input_video_pressed,       R.drawable.input_video_pressed_remix);
        map.put(R.drawable.left_status_profile,       R.drawable.msg_openprofile_remix);
        map.put(R.drawable.list_mute,                 R.drawable.list_mute_remix);
        map.put(R.drawable.list_pin,                  R.drawable.msg_pin_mini_remix);
        map.put(R.drawable.list_reorder,              R.drawable.list_reorder_remix);
        map.put(R.drawable.list_secret,               R.drawable.list_secret_remix);
        map.put(R.drawable.media_crop,                R.drawable.msg_photo_crop_remix);
        map.put(R.drawable.media_draw,                R.drawable.msg_photo_draw_remix);
        map.put(R.drawable.media_dual_camera2,        R.drawable.media_dual_camera2_remix);
        map.put(R.drawable.media_dual_camera2_shadow, R.drawable.media_dual_camera2_shadow_remix);
        map.put(R.drawable.media_flip,                R.drawable.msg_photo_flip_remix);
        map.put(R.drawable.media_like,                R.drawable.msg_reactions_remix);
        map.put(R.drawable.media_like_active,         R.drawable.media_like_active_remix);
        map.put(R.drawable.media_photo_flash_auto2,   R.drawable.media_photo_flash_auto2_remix);
        map.put(R.drawable.media_photo_flash_off2,    R.drawable.media_photo_flash_off2_remix);
        map.put(R.drawable.media_photo_flash_on2,     R.drawable.media_photo_flash_on2_remix);
        map.put(R.drawable.media_settings,            R.drawable.msg_photo_settings_remix);
        map.put(R.drawable.media_share,               R.drawable.msg_share_remix);
        map.put(R.drawable.menu_2sv,                  R.drawable.menu_2sv_remix);
        map.put(R.drawable.menu_2sv_on,               R.drawable.msg_policy_remix);
        map.put(R.drawable.menu_add_tab_24,           R.drawable.menu_add_tab_remix);
        map.put(R.drawable.menu_album_add,            R.drawable.menu_folder_add_remix);
        map.put(R.drawable.menu_birthday,             R.drawable.menu_birthday_remix);
        map.put(R.drawable.menu_browser_bookmarks,    R.drawable.msg_saved_remix);
        map.put(R.drawable.menu_clear_cache,          R.drawable.menu_clear_cache_remix);
        map.put(R.drawable.menu_clear_cookies,        R.drawable.msg_clear_remix);
        map.put(R.drawable.menu_clear_recent,         R.drawable.msg_clear_recent_remix);
        map.put(R.drawable.menu_day_mode_24,          R.drawable.menu_day_mode_remix);
        map.put(R.drawable.menu_devices,              R.drawable.msg_devices_remix);
        map.put(R.drawable.menu_download_round,       R.drawable.msg_download_remix);
        map.put(R.drawable.menu_edit_appearance,      R.drawable.menu_edit_appearance_remix);
        map.put(R.drawable.menu_edit_price,           R.drawable.menu_edit_price_remix);
        map.put(R.drawable.menu_feature_paid,         R.drawable.menu_feature_paid_remix);
        map.put(R.drawable.menu_feature_reactions,    R.drawable.menu_feature_reactions_remix);
        map.put(R.drawable.menu_feature_wallpaper,    R.drawable.msg_photos_remix);
        map.put(R.drawable.menu_folder_add,           R.drawable.menu_folder_add_remix);
        map.put(R.drawable.menu_gift,                 R.drawable.msg_gift_premium_remix);
        map.put(R.drawable.menu_hide_gift,            R.drawable.msg_archive_hide_remix);
        map.put(R.drawable.menu_instant_view,         R.drawable.menu_instant_view_remix);
        map.put(R.drawable.menu_intro,                R.drawable.menu_intro_remix);
        map.put(R.drawable.menu_invit_telegram,       R.drawable.menu_invite_telegram_remix);
        map.put(R.drawable.menu_link_create,          R.drawable.msg_link2_remix);
        map.put(R.drawable.menu_night_mode_24,        R.drawable.menu_night_mode_24_remix);
        map.put(R.drawable.menu_passkey_add,          R.drawable.menu_passkey_add_remix);
        map.put(R.drawable.menu_phone,                R.drawable.msg_calls_remix);
        map.put(R.drawable.menu_premium_clock,        R.drawable.menu_premium_clock_remix);
        map.put(R.drawable.menu_premium_clock_add,    R.drawable.menu_premium_clock_add_remix);
        map.put(R.drawable.menu_privacy_policy,       R.drawable.msg_policy_remix);
        map.put(R.drawable.menu_profile_colors,       R.drawable.menu_profile_colors_remix);
        map.put(R.drawable.menu_quality_hd,           R.drawable.menu_quality_hd_remix);
        map.put(R.drawable.menu_quality_sd,           R.drawable.menu_quality_sd_remix);
        map.put(R.drawable.menu_quote_delete,         R.drawable.menu_quote_delete_remix);
        map.put(R.drawable.menu_quote_specific,       R.drawable.menu_quote_remix);
        map.put(R.drawable.menu_reply,                R.drawable.msg_reply_remix);
        map.put(R.drawable.menu_select_quote,         R.drawable.menu_select_quote_remix);
        map.put(R.drawable.menu_share_off_24,         R.drawable.menu_share_off_remix);
        map.put(R.drawable.menu_share_on_24,          R.drawable.msg_share_remix);
        map.put(R.drawable.menu_shop,                 R.drawable.menu_shop_remix);
        map.put(R.drawable.menu_sticker_add,          R.drawable.menu_sticker_add_remix);
        map.put(R.drawable.menu_storage_path,         R.drawable.menu_storage_path_remix);
        map.put(R.drawable.menu_tag_delete,           R.drawable.menu_tag_delete_remix);
        map.put(R.drawable.menu_tag_edit,             R.drawable.menu_tag_edit_remix);
        map.put(R.drawable.menu_tag_filter,           R.drawable.menu_tag_filter_remix);
        map.put(R.drawable.menu_tag_plus,             R.drawable.menu_tag_plus_remix);
        map.put(R.drawable.menu_tag_rename,           R.drawable.menu_tag_rename_remix);
        map.put(R.drawable.menu_unsave_story,         R.drawable.menu_unsave_story_remix);
        map.put(R.drawable.menu_username_change,      R.drawable.menu_username_remix);
        map.put(R.drawable.menu_username_set,         R.drawable.menu_username_set_remix);
        map.put(R.drawable.menu_video_pip,            R.drawable.ic_goinline_remix);
        map.put(R.drawable.menu_views_recent,         R.drawable.msg_recent_remix);
        map.put(R.drawable.mini_quote,                R.drawable.mini_quote_remix);
        map.put(R.drawable.msg2_animations,           R.drawable.msg_played_remix);
        map.put(R.drawable.msg2_archived_stickers,    R.drawable.msg_archive_remix);
        map.put(R.drawable.msg2_ask_question,         R.drawable.msg_ask_question_remix);
        map.put(R.drawable.msg2_ask_question,         R.drawable.msg_ask_question_remix);
        map.put(R.drawable.msg2_autodelete,           R.drawable.msg_autodelete_remix);
        map.put(R.drawable.msg2_battery,              R.drawable.msg2_battery_remix);
        map.put(R.drawable.msg2_block2,               R.drawable.msg_block2_remix);
        map.put(R.drawable.msg2_call_earpiece,        R.drawable.msg_calls_remix);
        map.put(R.drawable.msg2_chats_add,            R.drawable.msg_chats_add_remix);
        map.put(R.drawable.msg2_data,                 R.drawable.msg_data_remix);
        map.put(R.drawable.msg2_devices,              R.drawable.msg_devices_remix);
        map.put(R.drawable.msg2_discussion,           R.drawable.msg_discussion_remix);
        map.put(R.drawable.msg2_email,                R.drawable.msg_email_remix);
        map.put(R.drawable.msg2_folder,               R.drawable.msg_folder_remix);
        map.put(R.drawable.msg2_gif,                  R.drawable.msg_gif_remix);
        map.put(R.drawable.msg2_help,                 R.drawable.msg_psa_remix);
        map.put(R.drawable.msg2_language,             R.drawable.msg_language_remix);
        map.put(R.drawable.msg2_link2,                R.drawable.msg_link2_remix);
        map.put(R.drawable.msg2_notifications,        R.drawable.msg_notifications_remix);
        map.put(R.drawable.msg2_permissions,          R.drawable.msg_permissions_remix);
        map.put(R.drawable.msg2_policy,               R.drawable.msg_policy_remix);
        map.put(R.drawable.msg2_reactions2,           R.drawable.msg_reactions_remix);
        map.put(R.drawable.msg2_secret,               R.drawable.msg_secret_remix);
        map.put(R.drawable.msg2_smile_status,         R.drawable.input_smile_remix);
        map.put(R.drawable.msg2_sticker,              R.drawable.msg_sticker_remix);
        map.put(R.drawable.msg2_trending,             R.drawable.msg_trending_remix);
        map.put(R.drawable.msg2_videocall,            R.drawable.msg_videocall_remix);
        map.put(R.drawable.msg_add,                   R.drawable.msg_add_remix);
        map.put(R.drawable.msg_addbio,                R.drawable.msg_addbio_remix);
        map.put(R.drawable.msg_addbot,                R.drawable.msg_addbot_remix);
        map.put(R.drawable.msg_addcontact,            R.drawable.msg_contact_add_remix);
        map.put(R.drawable.msg_addfolder,             R.drawable.msg_addfolder_remix);
        map.put(R.drawable.msg_addphoto,              R.drawable.msg_addphoto_remix);
        map.put(R.drawable.msg_admin_add,             R.drawable.msg_admin_add_remix);
        map.put(R.drawable.msg_admins,                R.drawable.msg_admins_remix);
        map.put(R.drawable.msg_allowspeak,            R.drawable.msg_allowspeak_remix);
        map.put(R.drawable.msg_archive,               R.drawable.msg_archive_remix);
        map.put(R.drawable.msg_archive_hide,          R.drawable.msg_archive_hide_remix);
        map.put(R.drawable.msg_archive_stories,       R.drawable.msg_menu_stories_remix);
        map.put(R.drawable.msg_arrow_back,            R.drawable.ic_ab_back_remix);
        map.put(R.drawable.msg_autodelete,            R.drawable.msg_autodelete_remix);
        map.put(R.drawable.msg_autodelete,            R.drawable.msg_autodelete_remix);
        map.put(R.drawable.msg_autodelete_1d,         R.drawable.msg_autodelete_1d_remix);
        map.put(R.drawable.msg_autodelete_1m,         R.drawable.msg_autodelete_1m_remix);
        map.put(R.drawable.msg_autodelete_1w,         R.drawable.msg_autodelete_1w_remix);
        map.put(R.drawable.msg_autodelete_badge2,     R.drawable.msg_autodelete_badge2_remix);
        map.put(R.drawable.msg_background,            R.drawable.msg_background_remix);
        map.put(R.drawable.msg_block,                 R.drawable.msg_block_remix);
        map.put(R.drawable.msg_block2,                R.drawable.msg_block2_remix);
        map.put(R.drawable.msg_bot,                   R.drawable.msg_bots_remix);
        map.put(R.drawable.msg_brightness_high,       R.drawable.msg_brightness_high_remix);
        map.put(R.drawable.msg_brightness_low,        R.drawable.msg_brightness_low_remix);
        map.put(R.drawable.msg_calendar,              R.drawable.msg_calendar_remix);
        map.put(R.drawable.msg_calendar2,             R.drawable.msg_calendar2_remix);
        map.put(R.drawable.msg_callback,              R.drawable.msg_calls_remix);
        map.put(R.drawable.msg_calls,                 R.drawable.msg_calls_remix);
        map.put(R.drawable.msg_calls_regular,         R.drawable.msg_calls_regular_remix);
        map.put(R.drawable.msg_camera,                R.drawable.msg_camera_remix);
        map.put(R.drawable.msg_cancel,                R.drawable.msg_cancel_remix);
        map.put(R.drawable.msg_channel,               R.drawable.msg_channel_remix);
        map.put(R.drawable.msg_chats_remove,          R.drawable.msg_chats_remove_remix);
        map.put(R.drawable.msg_clear,                 R.drawable.msg_clear_remix);
        map.put(R.drawable.msg_clear_input,           R.drawable.smiles_tab_clear_remix);
        map.put(R.drawable.msg_clear_recent,          R.drawable.msg_clear_recent_remix);
        map.put(R.drawable.msg_clearcache,            R.drawable.msg_delete_remix);
        map.put(R.drawable.msg_colors,                R.drawable.msg_colors_remix);
        map.put(R.drawable.msg_contact_add,           R.drawable.msg_contact_add_remix);
        map.put(R.drawable.msg_contacts,              R.drawable.msg_contacts_remix);
        map.put(R.drawable.msg_contacts_name,         R.drawable.msg_contacts_name_remix);
        map.put(R.drawable.msg_contacts_time,         R.drawable.msg_contacts_time_remix);
        map.put(R.drawable.msg_copy,                  R.drawable.msg_copy_remix);
        map.put(R.drawable.msg_copy_filled,           R.drawable.msg_copy_filled_remix);
        map.put(R.drawable.msg_copy_photo,            R.drawable.msg_copy_photo_remix);
        map.put(R.drawable.msg_current_location,      R.drawable.msg_current_location_remix);
        map.put(R.drawable.msg_customize,             R.drawable.msg_photo_settings_remix);
        map.put(R.drawable.msg_delete,                R.drawable.msg_delete_remix);
        map.put(R.drawable.msg_delete_auto,           R.drawable.msg_delete_auto_remix);
        map.put(R.drawable.msg_delete_filled,         R.drawable.msg_delete_filled_remix);
        map.put(R.drawable.msg_discuss,               R.drawable.msg_ask_question_remix);
        map.put(R.drawable.msg_discussion,            R.drawable.msg_discussion_remix);
        map.put(R.drawable.msg_download,              R.drawable.msg_download_remix);
        map.put(R.drawable.msg_edit,                  R.drawable.msg_edit_remix);
        map.put(R.drawable.msg_emoji_activities,      R.drawable.msg_emoji_activities_remix);
        map.put(R.drawable.msg_emoji_cat,             R.drawable.msg_emoji_cat_remix);
        map.put(R.drawable.msg_emoji_flags,           R.drawable.msg_emoji_flags_remix);
        map.put(R.drawable.msg_emoji_food,            R.drawable.msg_emoji_food_remix);
        map.put(R.drawable.msg_emoji_objects,         R.drawable.msg_emoji_objects_remix);
        map.put(R.drawable.msg_emoji_other,           R.drawable.msg_emoji_other_remix);
        map.put(R.drawable.msg_emoji_question,        R.drawable.msg_psa_remix);
        map.put(R.drawable.msg_emoji_recent,          R.drawable.msg_emoji_recent_remix);
        map.put(R.drawable.msg_emoji_smiles,          R.drawable.input_smile_remix);
        map.put(R.drawable.msg_emoji_stickers,        R.drawable.msg_sticker_remix);
        map.put(R.drawable.msg_emoji_travel,          R.drawable.msg_emoji_travel_remix);
        map.put(R.drawable.msg_endcall,               R.drawable.msg_endcall_remix);
        map.put(R.drawable.msg_fave,                  R.drawable.msg_fave_remix);
        map.put(R.drawable.msg_filehq,                R.drawable.msg_filehq_remix);
        map.put(R.drawable.msg_filled_blocked,        R.drawable.msg_filled_blocked_remix);
        map.put(R.drawable.msg_filled_data_calls,     R.drawable.msg_filled_data_calls_remix);
        map.put(R.drawable.msg_filled_data_files,     R.drawable.msg_filled_data_files_remix);
        map.put(R.drawable.msg_filled_data_messages,  R.drawable.msg_filled_data_messages_remix);
        map.put(R.drawable.msg_filled_data_music,     R.drawable.msg_filled_data_music_remix);
        map.put(R.drawable.msg_filled_data_photos,    R.drawable.msg_filled_data_photos_remix);
        map.put(R.drawable.msg_filled_data_received,  R.drawable.msg_filled_data_received_remix);
        map.put(R.drawable.msg_filled_data_sent,      R.drawable.msg_filled_data_sent_remix);
        map.put(R.drawable.msg_filled_data_videos,    R.drawable.msg_filled_data_videos_remix);
        map.put(R.drawable.msg_filled_data_voice,     R.drawable.msg_filled_data_voice_remix);
        map.put(R.drawable.msg_filled_datausage,      R.drawable.msg_filled_datausage_remix);
        map.put(R.drawable.msg_filled_menu_channels,  R.drawable.msg_filled_menu_channels_remix);
        map.put(R.drawable.msg_filled_menu_groups,    R.drawable.msg_filled_menu_groups_remix);
        map.put(R.drawable.msg_filled_menu_users,     R.drawable.msg_filled_menu_users_remix);
        map.put(R.drawable.msg_filled_sdcard,         R.drawable.msg_filled_sdcard_remix);
        map.put(R.drawable.msg_filled_shareout,       R.drawable.msg_filled_shareout_remix);
        map.put(R.drawable.msg_filled_storageusage,   R.drawable.msg_filled_storageusage_remix);
        map.put(R.drawable.msg_folders,               R.drawable.msg_folder_remix);
        map.put(R.drawable.msg_folders_archive,       R.drawable.msg_folders_archive_remix);
        map.put(R.drawable.msg_folders_bots,          R.drawable.msg_folders_bots_remix);
        map.put(R.drawable.msg_folders_channels,      R.drawable.msg_folders_channels_remix);
        map.put(R.drawable.msg_folders_groups,        R.drawable.msg_folders_groups_remix);
        map.put(R.drawable.msg_folders_muted,         R.drawable.msg_folders_muted_remix);
        map.put(R.drawable.msg_folders_private,       R.drawable.msg_folders_private_remix);
        map.put(R.drawable.msg_folders_read,          R.drawable.msg_folders_read_remix);
        map.put(R.drawable.msg_folders_requests,      R.drawable.msg_folders_requests_remix);
        map.put(R.drawable.msg_forward,               R.drawable.msg_share_remix);
        map.put(R.drawable.msg_forward_replace,       R.drawable.msg_forward_replace_remix);
        map.put(R.drawable.msg_gallery,               R.drawable.msg_gallery_remix);
        map.put(R.drawable.msg_gif,                   R.drawable.msg_gif_remix);
        map.put(R.drawable.msg_gif_add,               R.drawable.msg_gif_add_remix);
        map.put(R.drawable.msg_gift_premium,          R.drawable.msg_gift_premium_remix);
        map.put(R.drawable.msg_groups,                R.drawable.msg_groups_remix);
        map.put(R.drawable.msg_groups_create,         R.drawable.groups_create_remix);
        map.put(R.drawable.msg_header_draw,           R.drawable.msg_header_draw_remix);
        map.put(R.drawable.msg_header_share,          R.drawable.msg_header_share_remix);
        map.put(R.drawable.msg_help,                  R.drawable.msg_psa_remix);
        map.put(R.drawable.msg_home,                  R.drawable.msg_home_remix);
        map.put(R.drawable.msg_hybrid,                R.drawable.msg_hybrid_remix);
        map.put(R.drawable.msg_info,                  R.drawable.msg_info_remix);
        map.put(R.drawable.msg_input_attach2,         R.drawable.input_attach_remix);
        map.put(R.drawable.msg_input_gift,            R.drawable.msg_gift_premium_remix);
        map.put(R.drawable.msg_instant,               R.drawable.msg_instant_remix);
        map.put(R.drawable.msg_instant_link,          R.drawable.msg_instant_link_remix);
        map.put(R.drawable.msg_invited,               R.drawable.msg_invited_remix);
        map.put(R.drawable.msg_jobtitle,              R.drawable.msg_jobtitle_remix);
        map.put(R.drawable.msg_language,              R.drawable.msg_language_remix);
        map.put(R.drawable.msg_leave,                 R.drawable.msg_leave_remix);
        map.put(R.drawable.msg_link,                  R.drawable.msg_link2_remix);
        map.put(R.drawable.msg_link2,                 R.drawable.msg_link2_remix);
        map.put(R.drawable.msg_link_1,                R.drawable.msg_link_1_remix);
        map.put(R.drawable.msg_link_2,                R.drawable.msg_link_2_remix);
        map.put(R.drawable.msg_link_folder,           R.drawable.msg_link2_remix);
        map.put(R.drawable.msg_list,                  R.drawable.msg_list_remix);
        map.put(R.drawable.msg_location,              R.drawable.msg_location_remix);
        map.put(R.drawable.msg_location_alert,        R.drawable.msg_location_alert_remix);
        map.put(R.drawable.msg_location_alert2,       R.drawable.msg_bell_mute_remix);
        map.put(R.drawable.msg_log,                   R.drawable.msg_log_remix);
        map.put(R.drawable.msg_map,                   R.drawable.msg_map_remix);
        map.put(R.drawable.msg_map_type,              R.drawable.msg_map_type_remix);
        map.put(R.drawable.msg_markread,              R.drawable.msg_markread_remix);
        map.put(R.drawable.msg_markunread,            R.drawable.msg_markunread_remix);
        map.put(R.drawable.msg_mask,                  R.drawable.msg_mask_remix);
        map.put(R.drawable.msg_media,                 R.drawable.msg_media_remix);
        map.put(R.drawable.msg_mention,               R.drawable.menu_username_remix);
        map.put(R.drawable.msg_menu_stories,          R.drawable.msg_menu_stories_remix);
        map.put(R.drawable.msg_message,               R.drawable.msg_message_remix);
        map.put(R.drawable.msg_mini_autodelete,       R.drawable.msg_mini_autodelete_remix);
        map.put(R.drawable.msg_mini_autodelete_empty, R.drawable.msg_mini_autodelete_empty_remix);
        map.put(R.drawable.msg_mini_customize,        R.drawable.msg_mini_customize_remix);
        map.put(R.drawable.msg_mini_qr,               R.drawable.msg_mini_qr_remix);
        map.put(R.drawable.msg_mini_replystory,       R.drawable.msg_mini_replystory_remix);
        map.put(R.drawable.msg_mini_replystory2,      R.drawable.msg_mini_replystory_remix);
        map.put(R.drawable.msg_msgbubble,             R.drawable.msg_msgbubble3_remix);
        map.put(R.drawable.msg_msgbubble2,            R.drawable.msg_msgbubble2_remix);
        map.put(R.drawable.msg_msgbubble3,            R.drawable.msg_msgbubble3_remix);
        map.put(R.drawable.msg_mute,                  R.drawable.msg_mute_remix);
        map.put(R.drawable.msg_mute_1h,               R.drawable.msg_mute_1h_remix);
        map.put(R.drawable.msg_mute_period,           R.drawable.msg_mute_period_remix);
        map.put(R.drawable.msg_newphone,              R.drawable.msg_newphone_remix);
        map.put(R.drawable.msg_noise_off,             R.drawable.msg_noise_off_remix);
        map.put(R.drawable.msg_noise_on,              R.drawable.msg_noise_on_remix);
        map.put(R.drawable.msg_notifications,         R.drawable.msg_notifications_remix);
        map.put(R.drawable.msg_notspam,               R.drawable.msg_notspam_remix);
        map.put(R.drawable.msg_online,                R.drawable.msg_online_remix);
        map.put(R.drawable.msg_openin,                R.drawable.msg_instant_link_remix);
        map.put(R.drawable.msg_openprofile,           R.drawable.msg_openprofile_remix);
        map.put(R.drawable.msg_palette,               R.drawable.msg_theme_remix);
        map.put(R.drawable.msg_payment_address,       R.drawable.msg_location_remix);
        map.put(R.drawable.msg_payment_card,          R.drawable.msg_payment_card_remix);
        map.put(R.drawable.msg_payment_delivery,      R.drawable.msg_payment_delivery_remix);
        map.put(R.drawable.msg_payment_provider,      R.drawable.msg_payment_provider_remix);
        map.put(R.drawable.msg_permissions,           R.drawable.msg_permissions_remix);
        map.put(R.drawable.msg_photo_blur,            R.drawable.msg_photo_blur_remix);
        map.put(R.drawable.msg_photo_curve,           R.drawable.msg_photo_curve_remix);
        map.put(R.drawable.msg_photo_flip,            R.drawable.msg_photo_flip_remix);
        map.put(R.drawable.msg_photo_rotate,          R.drawable.msg_photo_rotate_remix);
        map.put(R.drawable.msg_photo_settings,        R.drawable.msg_photo_settings_remix);
        map.put(R.drawable.msg_photo_sticker,         R.drawable.msg_sticker_remix);
        map.put(R.drawable.msg_photo_switch2,         R.drawable.msg_retry_remix);
        map.put(R.drawable.msg_photo_text2,           R.drawable.msg_photo_text2_remix);
        map.put(R.drawable.msg_photoeditor,           R.drawable.msg_photo_draw_remix);
        map.put(R.drawable.msg_photos,                R.drawable.msg_photos_remix);
        map.put(R.drawable.msg_pin,                   R.drawable.msg_pin_remix);
        map.put(R.drawable.msg_pin_code,              R.drawable.msg_pin_code_remix);
        map.put(R.drawable.msg_pin_mini,              R.drawable.msg_pin_mini_remix);
        map.put(R.drawable.msg_pinnedlist,            R.drawable.msg_pinnedlist_remix);
        map.put(R.drawable.msg_played,                R.drawable.msg_played_remix);
        map.put(R.drawable.msg_policy,                R.drawable.msg_policy_remix);
        map.put(R.drawable.msg_pollstop,              R.drawable.msg_pollstop_remix);
        map.put(R.drawable.msg_psa,                   R.drawable.msg_psa_remix);
        map.put(R.drawable.msg_qr_mini,               R.drawable.msg_qrcode_mini_remix);
        map.put(R.drawable.msg_qrcode,                R.drawable.msg_qrcode_remix);
        map.put(R.drawable.msg_rate_down,             R.drawable.msg_rate_down_remix);
        map.put(R.drawable.msg_rate_up,               R.drawable.msg_rate_up_remix);
        map.put(R.drawable.msg_reactions,             R.drawable.msg_reactions_remix);
        map.put(R.drawable.msg_reactions2,            R.drawable.msg_reactions_remix);
        map.put(R.drawable.msg_reactions_filled,      R.drawable.msg_reactions_filled_remix);
        map.put(R.drawable.msg_recent,                R.drawable.msg_recent_remix);
        map.put(R.drawable.msg_remove,                R.drawable.msg_remove_remix);
        map.put(R.drawable.msg_removefolder,          R.drawable.msg_removefolder_remix);
        map.put(R.drawable.msg_repeat,                R.drawable.msg_repeat_remix);
        map.put(R.drawable.msg_replace,               R.drawable.msg_replace_remix);
        map.put(R.drawable.msg_reply_small,           R.drawable.msg_reply_small_remix);
        map.put(R.drawable.msg_report,                R.drawable.msg_report_other_remix);
        map.put(R.drawable.msg_report_drugs,          R.drawable.msg_report_drugs_remix);
        map.put(R.drawable.msg_report_fake,           R.drawable.msg_report_fake_remix);
        map.put(R.drawable.msg_report_other,          R.drawable.msg_report_other_remix);
        map.put(R.drawable.msg_report_personal,       R.drawable.msg_report_personal_remix);
        map.put(R.drawable.msg_report_violence,       R.drawable.msg_report_violence_remix);
        map.put(R.drawable.msg_report_xxx,            R.drawable.msg_report_xxx_remix);
        map.put(R.drawable.msg_requests,              R.drawable.msg_contact_add_remix);
        map.put(R.drawable.msg_reset,                 R.drawable.msg_reset_remix);
        map.put(R.drawable.msg_retry,                 R.drawable.msg_retry_remix);
        map.put(R.drawable.msg_round_file_s,          R.drawable.msg_round_file_remix);
        map.put(R.drawable.msg_satellite,             R.drawable.msg_satellite_remix);
        map.put(R.drawable.msg_save_story,            R.drawable.msg_stories_saved_remix);
        map.put(R.drawable.msg_saved,                 R.drawable.msg_saved_remix);
        map.put(R.drawable.msg_screencast,            R.drawable.msg_screencast_remix);
        map.put(R.drawable.msg_screencast_off,        R.drawable.msg_screencast_off_remix);
        map.put(R.drawable.msg_search,                R.drawable.msg_search_remix);
        map.put(R.drawable.msg_secret,                R.drawable.msg_secret_remix);
        map.put(R.drawable.msg_select,                R.drawable.msg_select_remix);
        map.put(R.drawable.msg_send,                  R.drawable.msg_send_remix);
        map.put(R.drawable.msg_sendfile,              R.drawable.msg_sendfile_remix);
        map.put(R.drawable.msg_settings,              R.drawable.msg_settings_remix);
        map.put(R.drawable.msg_settings_old,          R.drawable.msg_settings_remix);
        map.put(R.drawable.msg_share,                 R.drawable.msg_share_remix);
        map.put(R.drawable.msg_share_filled,          R.drawable.msg_share_filled_remix);
        map.put(R.drawable.msg_shareout,              R.drawable.msg_shareout_remix);
        map.put(R.drawable.msg_silent,                R.drawable.msg_silent_remix);
        map.put(R.drawable.msg_smile_status,          R.drawable.input_smile_remix);
        map.put(R.drawable.msg_speed,                 R.drawable.msg_speed_remix);
        map.put(R.drawable.msg_stats,                 R.drawable.msg_stats_remix);
        map.put(R.drawable.msg_sticker,               R.drawable.msg_sticker_remix);
        map.put(R.drawable.msg_stories_add,           R.drawable.msg_stories_add_remix);
        map.put(R.drawable.msg_stories_archive,       R.drawable.msg_stories_archive_remix);
        map.put(R.drawable.msg_stories_closefriends,  R.drawable.msg_stories_closefriends_remix);
        map.put(R.drawable.msg_stories_save,          R.drawable.msg_gallery_remix);
        map.put(R.drawable.msg_stories_saved,         R.drawable.msg_stories_saved_remix);
        map.put(R.drawable.msg_stories_stealth2,      R.drawable.msg_stories_stealth_remix);
        map.put(R.drawable.msg_theme,                 R.drawable.menu_profile_colors_remix);
        map.put(R.drawable.msg_tone_add,              R.drawable.msg_tone_add_remix);
        map.put(R.drawable.msg_tone_off,              R.drawable.msg_tone_off_remix);
        map.put(R.drawable.msg_tone_on,               R.drawable.msg_tone_on_remix);
        map.put(R.drawable.msg_topic_close,           R.drawable.msg_remove_remix);
        map.put(R.drawable.msg_topic_create,          R.drawable.msg_topic_create_remix);
        map.put(R.drawable.msg_topics,                R.drawable.msg_topics_remix);
        map.put(R.drawable.msg_translate,             R.drawable.msg_translate_remix);
        map.put(R.drawable.msg_unarchive,             R.drawable.msg_unarchive_remix);
        map.put(R.drawable.msg_unfave,                R.drawable.msg_unfave_remix);
        map.put(R.drawable.msg_ungroup,               R.drawable.msg_ungroup_remix);
        map.put(R.drawable.msg_unmute,                R.drawable.notifications_on_remix);
        map.put(R.drawable.msg_unpin,                 R.drawable.msg_unpin_remix);
        map.put(R.drawable.msg_unvote,                R.drawable.msg_unvote_remix);
        map.put(R.drawable.msg_user_remove,           R.drawable.msg_user_remove_remix);
        map.put(R.drawable.msg_usersearch,            R.drawable.msg_user_search_remix);
        map.put(R.drawable.msg_videocall,             R.drawable.msg_videocall_remix);
        map.put(R.drawable.msg_view_file,             R.drawable.msg_message_remix);
        map.put(R.drawable.msg_viewchats,             R.drawable.msg_discuss_remix);
        map.put(R.drawable.msg_viewintopic,           R.drawable.msg_viewintopic_remix);
        map.put(R.drawable.msg_viewreplies,           R.drawable.msg_viewreplies_remix);
        map.put(R.drawable.msg_views,                 R.drawable.msg_views_remix);
        map.put(R.drawable.msg_voice_bluetooth,       R.drawable.msg_voice_bluetooth_remix);
        map.put(R.drawable.msg_voice_headphones,      R.drawable.msg_voice_headphones_remix);
        map.put(R.drawable.msg_voice_phone,           R.drawable.msg_voice_phone_remix);
        map.put(R.drawable.msg_voice_pip,             R.drawable.msg_voice_pip_remix);
        map.put(R.drawable.msg_voice_speaker,         R.drawable.notifications_on_remix);
        map.put(R.drawable.msg_voicechat,             R.drawable.msg_voicechat_remix);
        map.put(R.drawable.msg_voicechat2,            R.drawable.msg_voicechat2_remix);
        map.put(R.drawable.msg_work,                  R.drawable.msg_work_remix);
        map.put(R.drawable.msg_zoomin,                R.drawable.msg_zoomin_remix);
        map.put(R.drawable.msg_zoomout,               R.drawable.msg_zoomout_remix);
        map.put(R.drawable.navbar_search_tag,         R.drawable.navbar_search_tag_remix);
        map.put(R.drawable.notifications_mute1h,      R.drawable.notifications_mute1h_remix);
        map.put(R.drawable.notifications_mute2d,      R.drawable.notifications_mute2d_remix);
        map.put(R.drawable.notifications_on,          R.drawable.notifications_on_remix);
        map.put(R.drawable.outline_add_account,       R.drawable.outline_add_account_remix);
        map.put(R.drawable.outline_caption_24,        R.drawable.outline_caption_remix);
        map.put(R.drawable.outline_groups_24,         R.drawable.msg_groups_remix);
        map.put(R.drawable.outline_header_search,     R.drawable.ic_ab_search_remix);
        map.put(R.drawable.outline_saved_24,          R.drawable.msg_saved_remix);
        map.put(R.drawable.outline_shield_check,      R.drawable.msg_policy_remix);
        map.put(R.drawable.outline_shield_plain_24,   R.drawable.outline_shield_plain_remix);
        map.put(R.drawable.permissions_camera1,       R.drawable.permissions_camera1_remix);
        map.put(R.drawable.permissions_camera2,       R.drawable.permissions_camera2_remix);
        map.put(R.drawable.permissions_gallery1,      R.drawable.permissions_gallery1_remix);
        map.put(R.drawable.permissions_gallery2,      R.drawable.permissions_gallery2_remix);
        map.put(R.drawable.photo_paint_brush,         R.drawable.photo_paint_brush_remix);
        map.put(R.drawable.photo_star,                R.drawable.msg_fave_remix);
        map.put(R.drawable.photo_undo,                R.drawable.photo_undo_remix);
        map.put(R.drawable.picker,                    R.drawable.ic_colorpicker_remix);
        map.put(R.drawable.pin,                       R.drawable.bot_location_remix);
        map.put(R.drawable.profile_discuss,           R.drawable.profile_discuss_remix);
        map.put(R.drawable.profile_phone,             R.drawable.profile_phone_remix);
        map.put(R.drawable.profile_video,             R.drawable.profile_video_remix);
        map.put(R.drawable.qr_flashlight,             R.drawable.qr_flashlight_remix);
        map.put(R.drawable.reactionbutton,            R.drawable.msg_reactions_remix);
        map.put(R.drawable.screencast_big,            R.drawable.screencast_big_remix);
        map.put(R.drawable.search_files_filled,       R.drawable.msg_round_file_remix);
        map.put(R.drawable.share,                     R.drawable.msg_filled_shareout_remix);
        map.put(R.drawable.share_arrow,               R.drawable.share_arrow_remix);
        map.put(R.drawable.smallanimationpin,         R.drawable.smallanimationpin_remix);
        map.put(R.drawable.smiles_inputsearch,        R.drawable.smiles_inputsearch_remix);
        map.put(R.drawable.smiles_tab_clear,          R.drawable.smiles_tab_clear_remix);
        map.put(R.drawable.smiles_tab_gif,            R.drawable.msg_gif_remix);
        map.put(R.drawable.smiles_tab_settings,       R.drawable.smiles_tab_settings_remix);
        map.put(R.drawable.smiles_tab_smiles,         R.drawable.input_smile_remix);
        map.put(R.drawable.smiles_tab_stickers,       R.drawable.msg_sticker_remix);
        map.put(R.drawable.stickers_empty,            R.drawable.stickers_empty_remix);
        map.put(R.drawable.stickers_favorites,        R.drawable.stickers_favorites_remix);
        map.put(R.drawable.stickers_gifs_trending,    R.drawable.stickers_gifs_trending_remix);
        map.put(R.drawable.stickers_recent,           R.drawable.msg_emoji_recent_remix);
        map.put(R.drawable.tabs_reorder,              R.drawable.tabs_reorder_remix);
        map.put(R.drawable.theme_picker,              R.drawable.theme_picker_remix);
        map.put(R.drawable.verified_area,             R.drawable.verified_area_remix);
        map.put(R.drawable.verified_check,            R.drawable.verified_check_remix);
        map.put(R.drawable.verified_profile,          R.drawable.verified_profile_remix);
        map.put(R.drawable.action_share,              R.drawable.share_remix);
        map.put(R.drawable.gift,                      R.drawable.gift_remix);
        map.put(R.drawable.leave,                     R.drawable.leave_remix);
        map.put(R.drawable.live_stream,               R.drawable.live_stream_remix);
        map.put(R.drawable.report,                    R.drawable.report_remix);
        map.put(R.drawable.filled_profile_message_24, R.drawable.message_remix);
        map.put(R.drawable.filled_profile_mute_24,    R.drawable.mute_remix);
        map.put(R.drawable.filled_profile_unmute_24,  R.drawable.unmute_remix);
        map.put(R.drawable.filled_profile_call_24,    R.drawable.call_remix);
        map.put(R.drawable.filled_profile_video_24,   R.drawable.video_remix);
        map.put(R.drawable.filled_profile_member_24,  R.drawable.join_remix);
        map.put(R.drawable.filled_profile_story,      R.drawable.story_remix);
        map.put(R.drawable.filled_profile_stop_24,    R.drawable.block_remix);
        map.put(R.drawable.filled_profile_photo,      R.drawable.camera_remix);
        map.put(R.drawable.filled_profile_edit_24,    R.drawable.group_edit_profile_remix);
        map.put(R.drawable.filled_profile_settings,   R.drawable.filled_profile_settings_remix);
        map.put(R.drawable.outline_profile_settings,  R.drawable.msg_settings_remix);
        map.put(R.drawable.tabs_chats_24,             R.drawable.tabs_chats_remix);
        map.put(R.drawable.tabs_chats_active_24,      R.drawable.tabs_chats_active_remix);
        map.put(R.drawable.tabs_contacts_24,          R.drawable.msg_openprofile_remix);
        map.put(R.drawable.tabs_contact_active_24,    R.drawable.tabs_contacts_active_remix);
        map.put(R.drawable.tabs_calls_24,             R.drawable.msg_calls_remix);
        map.put(R.drawable.tabs_calls_active_24,      R.drawable.tabs_calls_active_remix);
        map.put(R.drawable.ic_feed,                   R.drawable.ic_feed_remix);
        map.put(R.drawable.ic_feed_filled,            R.drawable.ic_feed_filled_remix);
        map.put(R.drawable.popup_fixed_alert,         R.drawable.popup_fixed_alert4);
        map.put(R.drawable.popup_fixed_alert2,        R.drawable.popup_fixed_alert4);
        map.put(R.drawable.popup_fixed_alert3,        R.drawable.popup_fixed_alert4);
        return map;
    }

    /**
     * {@code BaseIconPacks.java:624} — 563 put в exteraGram, 532 из них здесь.
     * Плюс 10 подмен, которых в exteraGram нет: они пришли из NagramX/AyuGram
     * ({@code java/tw/nekomimi/nekogram/ui/icons/SolarIcons.kt}) и сохранены, чтобы
     * полный набор не оказался беднее прежнего.
     */
    private static SparseIntArray buildSolar() {
        SparseIntArray map = new SparseIntArray(590);
        map.put(R.drawable.arrow_more,                R.drawable.arrow_more_solar);
        map.put(R.drawable.attach_send,               R.drawable.attach_send_solar);
        map.put(R.drawable.bot_file,                  R.drawable.msg_round_file_solar);
        map.put(R.drawable.bot_location,              R.drawable.bot_location_solar);
        map.put(R.drawable.calls_bluetooth,           R.drawable.calls_menu_bluetooth_solar);
        map.put(R.drawable.calls_camera_mini,         R.drawable.calls_camera_mini_solar);
        map.put(R.drawable.calls_decline,             R.drawable.calls_decline_solar);
        map.put(R.drawable.calls_headphones,          R.drawable.calls_menu_headset_solar);
        map.put(R.drawable.calls_menu_headset,        R.drawable.calls_menu_headset_solar);
        map.put(R.drawable.calls_menu_phone,          R.drawable.calls_menu_phone_solar);
        map.put(R.drawable.calls_mute_mini,           R.drawable.calls_mute_solar);
        map.put(R.drawable.calls_speaker,             R.drawable.calls_menu_speaker_solar);
        map.put(R.drawable.calls_unmute,              R.drawable.input_mic_pressed_solar);
        map.put(R.drawable.calls_video,               R.drawable.profile_video_solar);
        map.put(R.drawable.camera,                    R.drawable.camera_solar);
        map.put(R.drawable.camera_revert1,            R.drawable.camera_revert1_solar);
        map.put(R.drawable.camera_revert2,            R.drawable.camera_revert2_solar);
        map.put(R.drawable.chat_calls_video,          R.drawable.profile_video_solar);
        map.put(R.drawable.chat_calls_voice,          R.drawable.profile_phone_solar);
        map.put(R.drawable.chats_archive,             R.drawable.chats_archive_solar);
        map.put(R.drawable.chats_pin,                 R.drawable.msg_pin_solar);
        map.put(R.drawable.chats_replies,             R.drawable.chats_replies_solar);
        map.put(R.drawable.chats_saved,               R.drawable.chats_saved_solar);
        map.put(R.drawable.chats_unpin,               R.drawable.msg_unpin_solar);
        map.put(R.drawable.emoji_tabs_faves,          R.drawable.emoji_tabs_faves_solar);
        map.put(R.drawable.emoji_tabs_new1,           R.drawable.emoji_tabs_new1_solar);
        map.put(R.drawable.emoji_tabs_new2,           R.drawable.emoji_tabs_new2_solar);
        map.put(R.drawable.emoji_tabs_new3,           R.drawable.emoji_tabs_new3_solar);
        map.put(R.drawable.files_folder,              R.drawable.files_folder_solar);
        map.put(R.drawable.files_gallery,             R.drawable.files_gallery_solar);
        map.put(R.drawable.files_internal,            R.drawable.files_internal_solar);
        map.put(R.drawable.files_storage,             R.drawable.files_storage_solar);
        map.put(R.drawable.filled_add_photo,          R.drawable.filled_add_photo_solar);
        map.put(R.drawable.filled_button_reply,       R.drawable.msg_panel_reply_solar);
        map.put(R.drawable.filled_button_share,       R.drawable.filled_button_share_solar);
        map.put(R.drawable.filled_chatlist_mention,   R.drawable.filled_chatlist_mention_solar);
        map.put(R.drawable.filled_chatlist_poll,      R.drawable.filled_chatlist_poll_solar);
        map.put(R.drawable.filled_chatlist_reaction,  R.drawable.msg_reactions_filled_solar);
        map.put(R.drawable.filled_fire,               R.drawable.burn_solar);
        map.put(R.drawable.filled_forward,            R.drawable.filled_forward_solar);
        map.put(R.drawable.filled_link,               R.drawable.filled_link_solar);
        map.put(R.drawable.filled_open_message,       R.drawable.filled_open_message_solar);
        map.put(R.drawable.filled_reply_quote,        R.drawable.filled_reply_quote_solar);
        map.put(R.drawable.filled_reply_settings,     R.drawable.filled_reply_settings_solar);
        map.put(R.drawable.filter_airplane,           R.drawable.filter_airplane_solar);
        map.put(R.drawable.filter_all,                R.drawable.filter_all_solar);
        map.put(R.drawable.filter_book,               R.drawable.filter_book_solar);
        map.put(R.drawable.filter_bots,               R.drawable.filter_bots_solar);
        map.put(R.drawable.filter_cat,                R.drawable.filter_cat_solar);
        map.put(R.drawable.filter_channels,           R.drawable.filter_channels_solar);
        map.put(R.drawable.filter_crown,              R.drawable.filter_crown_solar);
        map.put(R.drawable.filter_custom,             R.drawable.filter_custom_solar);
        map.put(R.drawable.filter_favorite,           R.drawable.filter_favorite_solar);
        map.put(R.drawable.filter_flower,             R.drawable.filter_flower_solar);
        map.put(R.drawable.filter_game,               R.drawable.filter_game_solar);
        map.put(R.drawable.filter_group,              R.drawable.filter_group_solar);
        map.put(R.drawable.filter_home,               R.drawable.filter_home_solar);
        map.put(R.drawable.filter_light,              R.drawable.filter_light_solar);
        map.put(R.drawable.filter_like,               R.drawable.filter_like_solar);
        map.put(R.drawable.filter_love,               R.drawable.filter_love_solar);
        map.put(R.drawable.filter_mask,               R.drawable.filter_mask_solar);
        map.put(R.drawable.filter_money,              R.drawable.filter_money_solar);
        map.put(R.drawable.filter_note,               R.drawable.filter_note_solar);
        map.put(R.drawable.filter_palette,            R.drawable.filter_palette_solar);
        map.put(R.drawable.filter_party,              R.drawable.filter_party_solar);
        map.put(R.drawable.filter_private,            R.drawable.filter_private_solar);
        map.put(R.drawable.filter_setup,              R.drawable.filter_setup_solar);
        map.put(R.drawable.filter_sport,              R.drawable.filter_sport_solar);
        map.put(R.drawable.filter_study,              R.drawable.filter_study_solar);
        map.put(R.drawable.filter_trade,              R.drawable.filter_trade_solar);
        map.put(R.drawable.filter_travel,             R.drawable.filter_travel_solar);
        map.put(R.drawable.filter_unmuted,            R.drawable.filter_unmuted_solar);
        map.put(R.drawable.filter_unread,             R.drawable.filter_unread_solar);
        map.put(R.drawable.filter_work,               R.drawable.filter_work_solar);
        map.put(R.drawable.fingerprint,               R.drawable.fingerprint_solar);
        map.put(R.drawable.flash_auto,                R.drawable.flash_auto_solar);
        map.put(R.drawable.flash_off,                 R.drawable.flash_off_solar);
        map.put(R.drawable.flash_on,                  R.drawable.flash_on_solar);
        map.put(R.drawable.ghost,                     R.drawable.ghost_solar);
        map.put(R.drawable.group_edit,                R.drawable.group_edit_profile_solar);
        map.put(R.drawable.group_edit_profile,        R.drawable.group_edit_profile_solar);
        map.put(R.drawable.header_qr_24,              R.drawable.msg_qrcode_solar);
        map.put(R.drawable.ic_ab_back,                R.drawable.ic_ab_back_solar);
        map.put(R.drawable.ic_arrow_drop_down,        R.drawable.ic_arrow_drop_down_solar);
        map.put(R.drawable.ic_chatlist_add_2,         R.drawable.ic_chatlist_add_2_solar);
        map.put(R.drawable.ic_gallery_background,     R.drawable.ic_gallery_background_solar);
        map.put(R.drawable.ic_goinline,               R.drawable.ic_goinline_solar);
        map.put(R.drawable.ic_lock_header,            R.drawable.list_secret_solar);
        map.put(R.drawable.ic_masks_msk1,             R.drawable.ic_masks_msk1_solar);
        map.put(R.drawable.ic_outinline,              R.drawable.ic_outinline_solar);
        map.put(R.drawable.ic_send,                   R.drawable.ic_send_solar);
        map.put(R.drawable.input_attach,              R.drawable.input_attach_solar);
        map.put(R.drawable.input_bot1,                R.drawable.input_bot1_solar);
        map.put(R.drawable.input_bot2,                R.drawable.input_bot2_solar);
        map.put(R.drawable.input_calendar1,           R.drawable.input_calendar1_solar);
        map.put(R.drawable.input_calendar2,           R.drawable.input_calendar2_solar);
        map.put(R.drawable.input_forward,             R.drawable.input_forward_solar);
        map.put(R.drawable.input_gift_s,              R.drawable.msg_gift_premium_solar);
        map.put(R.drawable.input_keyboard,            R.drawable.input_keyboard_solar);
        map.put(R.drawable.input_message,             R.drawable.msg_discussion_solar);
        map.put(R.drawable.input_mic,                 R.drawable.input_mic_solar);
        map.put(R.drawable.input_mic_pressed,         R.drawable.input_mic_pressed_solar);
        map.put(R.drawable.input_notify_off,          R.drawable.msg_bell_mute_solar);
        map.put(R.drawable.input_notify_on,           R.drawable.msg_notifications_solar);
        map.put(R.drawable.input_reply,               R.drawable.input_reply_solar);
        map.put(R.drawable.input_schedule,            R.drawable.input_schedule_solar);
        map.put(R.drawable.input_smile,               R.drawable.input_smile_solar);
        map.put(R.drawable.input_suggest_paid_24,     R.drawable.input_suggest_paid_solar);
        map.put(R.drawable.input_video,               R.drawable.input_video_solar);
        map.put(R.drawable.input_video_pressed,       R.drawable.input_video_pressed_solar);
        map.put(R.drawable.left_status_profile,       R.drawable.msg_openprofile_solar);
        map.put(R.drawable.list_mute,                 R.drawable.list_mute_solar);
        map.put(R.drawable.list_pin,                  R.drawable.msg_pin_mini_solar);
        map.put(R.drawable.list_reorder,              R.drawable.list_reorder_solar);
        map.put(R.drawable.list_secret,               R.drawable.list_secret_solar);
        map.put(R.drawable.media_crop,                R.drawable.msg_photo_crop_solar);
        map.put(R.drawable.media_draw,                R.drawable.msg_photo_draw_solar);
        map.put(R.drawable.media_dual_camera2,        R.drawable.media_dual_camera2_solar);
        map.put(R.drawable.media_dual_camera2_shadow, R.drawable.media_dual_camera2_shadow_solar);
        map.put(R.drawable.media_flip,                R.drawable.msg_photo_flip_solar);
        map.put(R.drawable.media_like,                R.drawable.msg_reactions_solar);
        map.put(R.drawable.media_like_active,         R.drawable.media_like_active_solar);
        map.put(R.drawable.media_photo_flash_auto2,   R.drawable.media_photo_flash_auto2_solar);
        map.put(R.drawable.media_photo_flash_off2,    R.drawable.media_photo_flash_off2_solar);
        map.put(R.drawable.media_photo_flash_on2,     R.drawable.media_photo_flash_on2_solar);
        map.put(R.drawable.media_settings,            R.drawable.msg_photo_settings_solar);
        map.put(R.drawable.media_share,               R.drawable.msg_share_solar);
        map.put(R.drawable.menu_2sv,                  R.drawable.menu_2sv_solar);
        map.put(R.drawable.menu_2sv_on,               R.drawable.msg_policy_solar);
        map.put(R.drawable.menu_add_tab_24,           R.drawable.menu_add_tab_solar);
        map.put(R.drawable.menu_album_add,            R.drawable.menu_folder_add_solar);
        map.put(R.drawable.menu_browser_bookmarks,    R.drawable.msg_saved_solar);
        map.put(R.drawable.menu_clear_cache,          R.drawable.menu_clear_cache_solar);
        map.put(R.drawable.menu_clear_cookies,        R.drawable.msg_clear_solar);
        map.put(R.drawable.menu_clear_recent,         R.drawable.msg_clear_recent_solar);
        map.put(R.drawable.menu_day_mode_24,          R.drawable.menu_day_mode_solar);
        map.put(R.drawable.menu_devices,              R.drawable.msg_devices_solar);
        map.put(R.drawable.menu_download_round,       R.drawable.msg_download_solar);
        map.put(R.drawable.menu_edit_appearance,      R.drawable.menu_edit_appearance_solar);
        map.put(R.drawable.menu_edit_price,           R.drawable.menu_edit_price_solar);
        map.put(R.drawable.menu_feature_paid,         R.drawable.menu_feature_paid_solar);
        map.put(R.drawable.menu_feature_reactions,    R.drawable.menu_feature_reactions_solar);
        map.put(R.drawable.menu_feature_wallpaper,    R.drawable.msg_photos_solar);
        map.put(R.drawable.menu_folder_add,           R.drawable.menu_folder_add_solar);
        map.put(R.drawable.menu_gift,                 R.drawable.msg_gift_premium_solar);
        map.put(R.drawable.menu_hide_gift,            R.drawable.msg_archive_hide_solar);
        map.put(R.drawable.menu_instant_view,         R.drawable.menu_instant_view_solar);
        map.put(R.drawable.menu_intro,                R.drawable.menu_intro_solar);
        map.put(R.drawable.menu_invit_telegram,       R.drawable.menu_invite_telegram_solar);
        map.put(R.drawable.menu_link_create,          R.drawable.msg_link2_solar);
        map.put(R.drawable.menu_night_mode_24,        R.drawable.menu_night_mode_24_solar);
        map.put(R.drawable.menu_passkey_add,          R.drawable.menu_passkey_add_solar);
        map.put(R.drawable.menu_phone,                R.drawable.msg_calls_solar);
        map.put(R.drawable.menu_premium_clock,        R.drawable.menu_premium_clock_solar);
        map.put(R.drawable.menu_premium_clock_add,    R.drawable.menu_premium_clock_add_solar);
        map.put(R.drawable.menu_privacy_policy,       R.drawable.msg_policy_solar);
        map.put(R.drawable.menu_profile_colors,       R.drawable.menu_profile_colors_solar);
        map.put(R.drawable.menu_quality_hd,           R.drawable.menu_quality_hd_solar);
        map.put(R.drawable.menu_quality_sd,           R.drawable.menu_quality_sd_solar);
        map.put(R.drawable.menu_quote_delete,         R.drawable.menu_quote_delete_solar);
        map.put(R.drawable.menu_quote_specific,       R.drawable.menu_quote_solar);
        map.put(R.drawable.menu_reply,                R.drawable.msg_reply_solar);
        map.put(R.drawable.menu_select_quote,         R.drawable.menu_select_quote_solar);
        map.put(R.drawable.menu_share_off_24,         R.drawable.menu_share_off_solar);
        map.put(R.drawable.menu_share_on_24,          R.drawable.msg_share_solar);
        map.put(R.drawable.menu_shop,                 R.drawable.menu_shop_solar);
        map.put(R.drawable.menu_sticker_add,          R.drawable.menu_sticker_add_solar);
        map.put(R.drawable.menu_storage_path,         R.drawable.menu_storage_path_solar);
        map.put(R.drawable.menu_tag_delete,           R.drawable.menu_tag_delete_solar);
        map.put(R.drawable.menu_tag_edit,             R.drawable.menu_tag_edit_solar);
        map.put(R.drawable.menu_tag_filter,           R.drawable.menu_tag_filter_solar);
        map.put(R.drawable.menu_tag_plus,             R.drawable.menu_tag_plus_solar);
        map.put(R.drawable.menu_tag_rename,           R.drawable.menu_tag_rename_solar);
        map.put(R.drawable.menu_unsave_story,         R.drawable.menu_unsave_story_solar);
        map.put(R.drawable.menu_username_change,      R.drawable.menu_username_solar);
        map.put(R.drawable.menu_username_set,         R.drawable.menu_username_set_solar);
        map.put(R.drawable.menu_video_pip,            R.drawable.ic_goinline_solar);
        map.put(R.drawable.menu_views_recent,         R.drawable.msg_recent_solar);
        map.put(R.drawable.mini_quote,                R.drawable.mini_quote_solar);
        map.put(R.drawable.msg2_animations,           R.drawable.msg_played_solar);
        map.put(R.drawable.msg2_archived_stickers,    R.drawable.msg_archive_solar);
        map.put(R.drawable.msg2_ask_question,         R.drawable.msg_ask_question_solar);
        map.put(R.drawable.msg2_ask_question,         R.drawable.msg_ask_question_solar);
        map.put(R.drawable.msg2_autodelete,           R.drawable.msg_autodelete_solar);
        map.put(R.drawable.msg2_battery,              R.drawable.msg2_battery_solar);
        map.put(R.drawable.msg2_block2,               R.drawable.msg_block2_solar);
        map.put(R.drawable.msg2_call_earpiece,        R.drawable.msg_calls_solar);
        map.put(R.drawable.msg2_chats_add,            R.drawable.msg_chats_add_solar);
        map.put(R.drawable.msg2_data,                 R.drawable.msg_data_solar);
        map.put(R.drawable.msg2_devices,              R.drawable.msg_devices_solar);
        map.put(R.drawable.msg2_discussion,           R.drawable.msg_discussion_solar);
        map.put(R.drawable.msg2_email,                R.drawable.msg_email_solar);
        map.put(R.drawable.msg2_folder,               R.drawable.msg_folder_solar);
        map.put(R.drawable.msg2_gif,                  R.drawable.msg_gif_solar);
        map.put(R.drawable.msg2_help,                 R.drawable.msg_psa_solar);
        map.put(R.drawable.msg2_language,             R.drawable.msg_language_solar);
        map.put(R.drawable.msg2_link2,                R.drawable.msg_link2_solar);
        map.put(R.drawable.msg2_notifications,        R.drawable.msg_notifications_solar);
        map.put(R.drawable.msg2_permissions,          R.drawable.msg_permissions_solar);
        map.put(R.drawable.msg2_policy,               R.drawable.msg_policy_solar);
        map.put(R.drawable.msg2_reactions2,           R.drawable.msg_reactions_solar);
        map.put(R.drawable.msg2_secret,               R.drawable.msg_secret_solar);
        map.put(R.drawable.msg2_smile_status,         R.drawable.input_smile_solar);
        map.put(R.drawable.msg2_sticker,              R.drawable.msg_sticker_solar);
        map.put(R.drawable.msg2_trending,             R.drawable.msg_trending_solar);
        map.put(R.drawable.msg2_videocall,            R.drawable.msg_videocall_solar);
        map.put(R.drawable.msg_add,                   R.drawable.msg_add_solar);
        map.put(R.drawable.msg_addbio,                R.drawable.msg_addbio_solar);
        map.put(R.drawable.msg_addbot,                R.drawable.msg_addbot_solar);
        map.put(R.drawable.msg_addcontact,            R.drawable.msg_contact_add_solar);
        map.put(R.drawable.msg_addfolder,             R.drawable.msg_addfolder_solar);
        map.put(R.drawable.msg_addphoto,              R.drawable.msg_addphoto_solar);
        map.put(R.drawable.msg_admin_add,             R.drawable.msg_admin_add_solar);
        map.put(R.drawable.msg_admins,                R.drawable.msg_admins_solar);
        map.put(R.drawable.msg_allowspeak,            R.drawable.msg_allowspeak_solar);
        map.put(R.drawable.msg_archive,               R.drawable.msg_archive_solar);
        map.put(R.drawable.msg_archive_hide,          R.drawable.msg_archive_hide_solar);
        map.put(R.drawable.msg_archive_stories,       R.drawable.msg_menu_stories_solar);
        map.put(R.drawable.msg_arrow_back,            R.drawable.ic_ab_back_solar);
        map.put(R.drawable.msg_autodelete,            R.drawable.msg_autodelete_solar);
        map.put(R.drawable.msg_autodelete_1d,         R.drawable.msg_autodelete_1d_solar);
        map.put(R.drawable.msg_autodelete_1m,         R.drawable.msg_autodelete_1m_solar);
        map.put(R.drawable.msg_autodelete_1w,         R.drawable.msg_autodelete_1w_solar);
        map.put(R.drawable.msg_autodelete_badge2,     R.drawable.msg_autodelete_badge2_solar);
        map.put(R.drawable.msg_background,            R.drawable.msg_background_solar);
        map.put(R.drawable.msg_block,                 R.drawable.msg_block_solar);
        map.put(R.drawable.msg_block2,                R.drawable.msg_block2_solar);
        map.put(R.drawable.msg_bot,                   R.drawable.msg_bots_solar);
        map.put(R.drawable.msg_brightness_high,       R.drawable.msg_brightness_high_solar);
        map.put(R.drawable.msg_brightness_low,        R.drawable.msg_brightness_low_solar);
        map.put(R.drawable.msg_calendar,              R.drawable.msg_calendar_solar);
        map.put(R.drawable.msg_calendar2,             R.drawable.msg_calendar2_solar);
        map.put(R.drawable.msg_callback,              R.drawable.msg_calls_solar);
        map.put(R.drawable.msg_calls,                 R.drawable.msg_calls_solar);
        map.put(R.drawable.msg_calls_regular,         R.drawable.msg_calls_regular_solar);
        map.put(R.drawable.msg_camera,                R.drawable.msg_camera_solar);
        map.put(R.drawable.msg_cancel,                R.drawable.msg_cancel_solar);
        map.put(R.drawable.msg_channel,               R.drawable.msg_channel_solar);
        map.put(R.drawable.msg_chats_remove,          R.drawable.msg_chats_remove_solar);
        map.put(R.drawable.msg_clear,                 R.drawable.msg_clear_solar);
        map.put(R.drawable.msg_clear_input,           R.drawable.smiles_tab_clear_solar);
        map.put(R.drawable.msg_clear_recent,          R.drawable.msg_clear_recent_solar);
        map.put(R.drawable.msg_clearcache,            R.drawable.msg_delete_solar);
        map.put(R.drawable.msg_colors,                R.drawable.msg_colors_solar);
        map.put(R.drawable.msg_contact_add,           R.drawable.msg_contact_add_solar);
        map.put(R.drawable.msg_contacts,              R.drawable.msg_contacts_solar);
        map.put(R.drawable.msg_contacts_name,         R.drawable.msg_contacts_name_solar);
        map.put(R.drawable.msg_contacts_time,         R.drawable.msg_contacts_time_solar);
        map.put(R.drawable.msg_copy,                  R.drawable.msg_copy_solar);
        map.put(R.drawable.msg_copy_filled,           R.drawable.msg_copy_filled_solar);
        map.put(R.drawable.msg_copy_photo,            R.drawable.msg_copy_photo_solar);
        map.put(R.drawable.msg_current_location,      R.drawable.msg_current_location_solar);
        map.put(R.drawable.msg_customize,             R.drawable.msg_photo_settings_solar);
        map.put(R.drawable.msg_delete,                R.drawable.msg_delete_solar);
        map.put(R.drawable.msg_delete_auto,           R.drawable.msg_delete_auto_solar);
        map.put(R.drawable.msg_delete_filled,         R.drawable.msg_delete_filled_solar);
        map.put(R.drawable.msg_discuss,               R.drawable.msg_ask_question_solar);
        map.put(R.drawable.msg_discussion,            R.drawable.msg_discussion_solar);
        map.put(R.drawable.msg_download,              R.drawable.msg_download_solar);
        map.put(R.drawable.msg_edit,                  R.drawable.msg_edit_solar);
        map.put(R.drawable.msg_emoji_activities,      R.drawable.msg_emoji_activities_solar);
        map.put(R.drawable.msg_emoji_cat,             R.drawable.msg_emoji_cat_solar);
        map.put(R.drawable.msg_emoji_flags,           R.drawable.msg_emoji_flags_solar);
        map.put(R.drawable.msg_emoji_food,            R.drawable.msg_emoji_food_solar);
        map.put(R.drawable.msg_emoji_objects,         R.drawable.msg_emoji_objects_solar);
        map.put(R.drawable.msg_emoji_other,           R.drawable.msg_emoji_other_solar);
        map.put(R.drawable.msg_emoji_question,        R.drawable.msg_psa_solar);
        map.put(R.drawable.msg_emoji_recent,          R.drawable.msg_emoji_recent_solar);
        map.put(R.drawable.msg_emoji_smiles,          R.drawable.input_smile_solar);
        map.put(R.drawable.msg_emoji_stickers,        R.drawable.msg_sticker_solar);
        map.put(R.drawable.msg_emoji_travel,          R.drawable.msg_emoji_travel_solar);
        map.put(R.drawable.msg_endcall,               R.drawable.msg_endcall_solar);
        map.put(R.drawable.msg_fave,                  R.drawable.msg_fave_solar);
        map.put(R.drawable.msg_filehq,                R.drawable.msg_filehq_solar);
        map.put(R.drawable.msg_filled_blocked,        R.drawable.msg_filled_blocked_solar);
        map.put(R.drawable.msg_filled_data_calls,     R.drawable.msg_filled_data_calls_solar);
        map.put(R.drawable.msg_filled_data_files,     R.drawable.msg_filled_data_files_solar);
        map.put(R.drawable.msg_filled_data_messages,  R.drawable.msg_filled_data_messages_solar);
        map.put(R.drawable.msg_filled_data_music,     R.drawable.msg_filled_data_music_solar);
        map.put(R.drawable.msg_filled_data_photos,    R.drawable.msg_filled_data_photos_solar);
        map.put(R.drawable.msg_filled_data_received,  R.drawable.msg_filled_data_received_solar);
        map.put(R.drawable.msg_filled_data_sent,      R.drawable.msg_filled_data_sent_solar);
        map.put(R.drawable.msg_filled_data_videos,    R.drawable.msg_filled_data_videos_solar);
        map.put(R.drawable.msg_filled_data_voice,     R.drawable.msg_filled_data_voice_solar);
        map.put(R.drawable.msg_filled_datausage,      R.drawable.msg_filled_datausage_solar);
        map.put(R.drawable.msg_filled_menu_channels,  R.drawable.msg_filled_menu_channels_solar);
        map.put(R.drawable.msg_filled_menu_groups,    R.drawable.msg_filled_menu_groups_solar);
        map.put(R.drawable.msg_filled_menu_users,     R.drawable.msg_filled_menu_users_solar);
        map.put(R.drawable.msg_filled_sdcard,         R.drawable.msg_filled_sdcard_solar);
        map.put(R.drawable.msg_filled_shareout,       R.drawable.msg_filled_shareout_solar);
        map.put(R.drawable.msg_filled_storageusage,   R.drawable.msg_filled_storageusage_solar);
        map.put(R.drawable.msg_folders,               R.drawable.msg_folder_solar);
        map.put(R.drawable.msg_folders_archive,       R.drawable.msg_folders_archive_solar);
        map.put(R.drawable.msg_folders_bots,          R.drawable.msg_folders_bots_solar);
        map.put(R.drawable.msg_folders_channels,      R.drawable.msg_folders_channels_solar);
        map.put(R.drawable.msg_folders_groups,        R.drawable.msg_folders_groups_solar);
        map.put(R.drawable.msg_folders_muted,         R.drawable.msg_folders_muted_solar);
        map.put(R.drawable.msg_folders_private,       R.drawable.msg_folders_private_solar);
        map.put(R.drawable.msg_folders_read,          R.drawable.msg_folders_read_solar);
        map.put(R.drawable.msg_folders_requests,      R.drawable.msg_folders_requests_solar);
        map.put(R.drawable.msg_forward,               R.drawable.msg_share_solar);
        map.put(R.drawable.msg_forward_replace,       R.drawable.msg_forward_replace_solar);
        map.put(R.drawable.msg_gallery,               R.drawable.msg_gallery_solar);
        map.put(R.drawable.msg_gif,                   R.drawable.msg_gif_solar);
        map.put(R.drawable.msg_gif_add,               R.drawable.msg_gif_add_solar);
        map.put(R.drawable.msg_gift_premium,          R.drawable.msg_gift_premium_solar);
        map.put(R.drawable.msg_groups,                R.drawable.msg_groups_solar);
        map.put(R.drawable.msg_groups_create,         R.drawable.groups_create_solar);
        map.put(R.drawable.msg_header_draw,           R.drawable.msg_header_draw_solar);
        map.put(R.drawable.msg_header_share,          R.drawable.msg_header_share_solar);
        map.put(R.drawable.msg_help,                  R.drawable.msg_psa_solar);
        map.put(R.drawable.msg_home,                  R.drawable.msg_home_solar);
        map.put(R.drawable.msg_hybrid,                R.drawable.msg_hybrid_solar);
        map.put(R.drawable.msg_info,                  R.drawable.msg_info_solar);
        map.put(R.drawable.msg_input_attach2,         R.drawable.input_attach_solar);
        map.put(R.drawable.msg_input_gift,            R.drawable.msg_gift_premium_solar);
        map.put(R.drawable.msg_instant,               R.drawable.msg_instant_solar);
        map.put(R.drawable.msg_instant_link,          R.drawable.msg_instant_link_solar);
        map.put(R.drawable.msg_invited,               R.drawable.msg_invited_solar);
        map.put(R.drawable.msg_jobtitle,              R.drawable.msg_jobtitle_solar);
        map.put(R.drawable.msg_language,              R.drawable.msg_language_solar);
        map.put(R.drawable.msg_leave,                 R.drawable.msg_leave_solar);
        map.put(R.drawable.msg_link,                  R.drawable.msg_link2_solar);
        map.put(R.drawable.msg_link2,                 R.drawable.msg_link2_solar);
        map.put(R.drawable.msg_link_1,                R.drawable.msg_link_1_solar);
        map.put(R.drawable.msg_link_2,                R.drawable.msg_link_2_solar);
        map.put(R.drawable.msg_link_folder,           R.drawable.msg_link2_solar);
        map.put(R.drawable.msg_list,                  R.drawable.msg_list_solar);
        map.put(R.drawable.msg_location,              R.drawable.msg_location_solar);
        map.put(R.drawable.msg_location_alert,        R.drawable.msg_location_alert_solar);
        map.put(R.drawable.msg_location_alert2,       R.drawable.msg_bell_mute_solar);
        map.put(R.drawable.msg_log,                   R.drawable.msg_log_solar);
        map.put(R.drawable.msg_map,                   R.drawable.msg_map_solar);
        map.put(R.drawable.msg_map_type,              R.drawable.msg_map_type_solar);
        map.put(R.drawable.msg_markread,              R.drawable.msg_markread_solar);
        map.put(R.drawable.msg_markunread,            R.drawable.msg_markunread_solar);
        map.put(R.drawable.msg_mask,                  R.drawable.msg_mask_solar);
        map.put(R.drawable.msg_media,                 R.drawable.msg_media_solar);
        map.put(R.drawable.msg_mention,               R.drawable.menu_username_solar);
        map.put(R.drawable.msg_menu_stories,          R.drawable.msg_menu_stories_solar);
        map.put(R.drawable.msg_message,               R.drawable.msg_message_solar);
        map.put(R.drawable.msg_mini_autodelete,       R.drawable.msg_mini_autodelete_solar);
        map.put(R.drawable.msg_mini_autodelete_empty, R.drawable.msg_mini_autodelete_empty_solar);
        map.put(R.drawable.msg_mini_customize,        R.drawable.msg_mini_customize_solar);
        map.put(R.drawable.msg_mini_qr,               R.drawable.msg_mini_qr_solar);
        map.put(R.drawable.msg_mini_replystory,       R.drawable.msg_mini_replystory_solar);
        map.put(R.drawable.msg_mini_replystory2,      R.drawable.msg_mini_replystory_solar);
        map.put(R.drawable.msg_msgbubble,             R.drawable.msg_msgbubble3_solar);
        map.put(R.drawable.msg_msgbubble2,            R.drawable.msg_msgbubble2_solar);
        map.put(R.drawable.msg_msgbubble3,            R.drawable.msg_msgbubble3_solar);
        map.put(R.drawable.msg_mute,                  R.drawable.msg_mute_solar);
        map.put(R.drawable.msg_mute_1h,               R.drawable.msg_mute_1h_solar);
        map.put(R.drawable.msg_mute_period,           R.drawable.msg_mute_period_solar);
        map.put(R.drawable.msg_newphone,              R.drawable.msg_newphone_solar);
        map.put(R.drawable.msg_noise_off,             R.drawable.msg_noise_off_solar);
        map.put(R.drawable.msg_noise_on,              R.drawable.msg_noise_on_solar);
        map.put(R.drawable.msg_notifications,         R.drawable.msg_notifications_solar);
        map.put(R.drawable.msg_notspam,               R.drawable.msg_notspam_solar);
        map.put(R.drawable.msg_online,                R.drawable.msg_online_solar);
        map.put(R.drawable.msg_openin,                R.drawable.msg_instant_link_solar);
        map.put(R.drawable.msg_openprofile,           R.drawable.msg_openprofile_solar);
        map.put(R.drawable.msg_palette,               R.drawable.msg_theme_solar);
        map.put(R.drawable.msg_payment_address,       R.drawable.msg_location_solar);
        map.put(R.drawable.msg_payment_card,          R.drawable.msg_payment_card_solar);
        map.put(R.drawable.msg_payment_delivery,      R.drawable.msg_payment_delivery_solar);
        map.put(R.drawable.msg_payment_provider,      R.drawable.msg_payment_provider_solar);
        map.put(R.drawable.msg_permissions,           R.drawable.msg_permissions_solar);
        map.put(R.drawable.msg_photo_blur,            R.drawable.msg_photo_blur_solar);
        map.put(R.drawable.msg_photo_curve,           R.drawable.msg_photo_curve_solar);
        map.put(R.drawable.msg_photo_flip,            R.drawable.msg_photo_flip_solar);
        map.put(R.drawable.msg_photo_rotate,          R.drawable.msg_photo_rotate_solar);
        map.put(R.drawable.msg_photo_settings,        R.drawable.msg_photo_settings_solar);
        map.put(R.drawable.msg_photo_sticker,         R.drawable.msg_sticker_solar);
        map.put(R.drawable.msg_photo_switch2,         R.drawable.msg_retry_solar);
        map.put(R.drawable.msg_photo_text2,           R.drawable.msg_photo_text2_solar);
        map.put(R.drawable.msg_photoeditor,           R.drawable.msg_photo_draw_solar);
        map.put(R.drawable.msg_photos,                R.drawable.msg_photos_solar);
        map.put(R.drawable.msg_pin,                   R.drawable.msg_pin_solar);
        map.put(R.drawable.msg_pin_code,              R.drawable.msg_pin_code_solar);
        map.put(R.drawable.msg_pin_mini,              R.drawable.msg_pin_mini_solar);
        map.put(R.drawable.msg_pinnedlist,            R.drawable.msg_pinnedlist_solar);
        map.put(R.drawable.msg_played,                R.drawable.msg_played_solar);
        map.put(R.drawable.msg_policy,                R.drawable.msg_policy_solar);
        map.put(R.drawable.msg_pollstop,              R.drawable.msg_pollstop_solar);
        map.put(R.drawable.msg_psa,                   R.drawable.msg_psa_solar);
        map.put(R.drawable.msg_qr_mini,               R.drawable.msg_qrcode_mini_solar);
        map.put(R.drawable.msg_qrcode,                R.drawable.msg_qrcode_solar);
        map.put(R.drawable.msg_rate_down,             R.drawable.msg_rate_down_solar);
        map.put(R.drawable.msg_rate_up,               R.drawable.msg_rate_up_solar);
        map.put(R.drawable.msg_reactions,             R.drawable.msg_reactions_solar);
        map.put(R.drawable.msg_reactions2,            R.drawable.msg_reactions_solar);
        map.put(R.drawable.msg_reactions_filled,      R.drawable.msg_reactions_filled_solar);
        map.put(R.drawable.msg_recent,                R.drawable.msg_recent_solar);
        map.put(R.drawable.msg_remove,                R.drawable.msg_remove_solar);
        map.put(R.drawable.msg_removefolder,          R.drawable.msg_removefolder_solar);
        map.put(R.drawable.msg_repeat,                R.drawable.msg_repeat_solar);
        map.put(R.drawable.msg_replace,               R.drawable.msg_replace_solar);
        map.put(R.drawable.msg_reply_small,           R.drawable.msg_reply_small_solar);
        map.put(R.drawable.msg_report,                R.drawable.msg_report_other_solar);
        map.put(R.drawable.msg_report_drugs,          R.drawable.msg_report_drugs_solar);
        map.put(R.drawable.msg_report_fake,           R.drawable.msg_report_fake_solar);
        map.put(R.drawable.msg_report_other,          R.drawable.msg_report_other_solar);
        map.put(R.drawable.msg_report_personal,       R.drawable.msg_report_personal_solar);
        map.put(R.drawable.msg_report_violence,       R.drawable.msg_report_violence_solar);
        map.put(R.drawable.msg_report_xxx,            R.drawable.msg_report_xxx_solar);
        map.put(R.drawable.msg_requests,              R.drawable.msg_contact_add_solar);
        map.put(R.drawable.msg_reset,                 R.drawable.msg_reset_solar);
        map.put(R.drawable.msg_retry,                 R.drawable.msg_retry_solar);
        map.put(R.drawable.msg_round_file_s,          R.drawable.msg_round_file_solar);
        map.put(R.drawable.msg_satellite,             R.drawable.msg_satellite_solar);
        map.put(R.drawable.msg_save_story,            R.drawable.msg_stories_saved_solar);
        map.put(R.drawable.msg_saved,                 R.drawable.msg_saved_solar);
        map.put(R.drawable.msg_screencast,            R.drawable.msg_screencast_solar);
        map.put(R.drawable.msg_screencast_off,        R.drawable.msg_screencast_off_solar);
        map.put(R.drawable.msg_search,                R.drawable.msg_search_solar);
        map.put(R.drawable.msg_secret,                R.drawable.msg_secret_solar);
        map.put(R.drawable.msg_select,                R.drawable.msg_select_solar);
        map.put(R.drawable.msg_send,                  R.drawable.msg_send_solar);
        map.put(R.drawable.msg_sendfile,              R.drawable.msg_sendfile_solar);
        map.put(R.drawable.msg_settings,              R.drawable.msg_settings_solar);
        map.put(R.drawable.msg_settings_old,          R.drawable.msg_settings_solar);
        map.put(R.drawable.msg_share,                 R.drawable.msg_share_solar);
        map.put(R.drawable.msg_share_filled,          R.drawable.msg_share_filled_solar);
        map.put(R.drawable.msg_shareout,              R.drawable.msg_shareout_solar);
        map.put(R.drawable.msg_silent,                R.drawable.msg_silent_solar);
        map.put(R.drawable.msg_smile_status,          R.drawable.input_smile_solar);
        map.put(R.drawable.msg_speed,                 R.drawable.msg_speed_solar);
        map.put(R.drawable.msg_stats,                 R.drawable.msg_stats_solar);
        map.put(R.drawable.msg_sticker,               R.drawable.msg_sticker_solar);
        map.put(R.drawable.msg_stories_add,           R.drawable.msg_stories_add_solar);
        map.put(R.drawable.msg_stories_archive,       R.drawable.msg_stories_archive_solar);
        map.put(R.drawable.msg_stories_closefriends,  R.drawable.msg_stories_closefriends_solar);
        map.put(R.drawable.msg_stories_save,          R.drawable.msg_gallery_solar);
        map.put(R.drawable.msg_stories_saved,         R.drawable.msg_stories_saved_solar);
        map.put(R.drawable.msg_stories_stealth2,      R.drawable.msg_stories_stealth_solar);
        map.put(R.drawable.msg_theme,                 R.drawable.menu_profile_colors_solar);
        map.put(R.drawable.msg_tone_add,              R.drawable.msg_tone_add_solar);
        map.put(R.drawable.msg_tone_off,              R.drawable.msg_tone_off_solar);
        map.put(R.drawable.msg_tone_on,               R.drawable.msg_tone_on_solar);
        map.put(R.drawable.msg_topic_close,           R.drawable.msg_remove_solar);
        map.put(R.drawable.msg_topic_create,          R.drawable.msg_topic_create_solar);
        map.put(R.drawable.msg_topics,                R.drawable.msg_topics_solar);
        map.put(R.drawable.msg_translate,             R.drawable.msg_translate_solar);
        map.put(R.drawable.msg_unarchive,             R.drawable.msg_unarchive_solar);
        map.put(R.drawable.msg_unfave,                R.drawable.msg_unfave_solar);
        map.put(R.drawable.msg_ungroup,               R.drawable.msg_ungroup_solar);
        map.put(R.drawable.msg_unmute,                R.drawable.notifications_on_solar);
        map.put(R.drawable.msg_unpin,                 R.drawable.msg_unpin_solar);
        map.put(R.drawable.msg_unvote,                R.drawable.msg_unvote_solar);
        map.put(R.drawable.msg_user_remove,           R.drawable.msg_user_remove_solar);
        map.put(R.drawable.msg_usersearch,            R.drawable.msg_user_search_solar);
        map.put(R.drawable.msg_videocall,             R.drawable.msg_videocall_solar);
        map.put(R.drawable.msg_view_file,             R.drawable.msg_message_solar);
        map.put(R.drawable.msg_viewchats,             R.drawable.msg_discuss_solar);
        map.put(R.drawable.msg_viewintopic,           R.drawable.msg_viewintopic_solar);
        map.put(R.drawable.msg_viewreplies,           R.drawable.msg_viewreplies_solar);
        map.put(R.drawable.msg_views,                 R.drawable.msg_views_solar);
        map.put(R.drawable.msg_voice_bluetooth,       R.drawable.msg_voice_bluetooth_solar);
        map.put(R.drawable.msg_voice_headphones,      R.drawable.msg_voice_headphones_solar);
        map.put(R.drawable.msg_voice_phone,           R.drawable.msg_voice_phone_solar);
        map.put(R.drawable.msg_voice_pip,             R.drawable.msg_voice_pip_solar);
        map.put(R.drawable.msg_voice_speaker,         R.drawable.notifications_on_solar);
        map.put(R.drawable.msg_voicechat,             R.drawable.msg_voicechat_solar);
        map.put(R.drawable.msg_voicechat2,            R.drawable.msg_voicechat2_solar);
        map.put(R.drawable.msg_work,                  R.drawable.msg_work_solar);
        map.put(R.drawable.msg_zoomin,                R.drawable.msg_zoomin_solar);
        map.put(R.drawable.msg_zoomout,               R.drawable.msg_zoomout_solar);
        map.put(R.drawable.navbar_search_tag,         R.drawable.navbar_search_tag_solar);
        map.put(R.drawable.notifications_mute1h,      R.drawable.notifications_mute1h_solar);
        map.put(R.drawable.notifications_mute2d,      R.drawable.notifications_mute2d_solar);
        map.put(R.drawable.notifications_on,          R.drawable.notifications_on_solar);
        map.put(R.drawable.outline_add_account,       R.drawable.outline_add_account_solar);
        map.put(R.drawable.outline_caption_24,        R.drawable.outline_caption_solar);
        map.put(R.drawable.outline_groups_24,         R.drawable.msg_groups_solar);
        map.put(R.drawable.outline_header_search,     R.drawable.ic_ab_search_solar);
        map.put(R.drawable.outline_saved_24,          R.drawable.msg_saved_solar);
        map.put(R.drawable.outline_shield_check,      R.drawable.msg_policy_solar);
        map.put(R.drawable.outline_shield_plain_24,   R.drawable.outline_shield_plain_solar);
        map.put(R.drawable.permissions_camera1,       R.drawable.permissions_camera1_solar);
        map.put(R.drawable.permissions_camera2,       R.drawable.permissions_camera2_solar);
        map.put(R.drawable.permissions_gallery1,      R.drawable.permissions_gallery1_solar);
        map.put(R.drawable.permissions_gallery2,      R.drawable.permissions_gallery2_solar);
        map.put(R.drawable.photo_paint_brush,         R.drawable.photo_paint_brush_solar);
        map.put(R.drawable.photo_star,                R.drawable.msg_fave_solar);
        map.put(R.drawable.photo_undo,                R.drawable.photo_undo_solar);
        map.put(R.drawable.picker,                    R.drawable.ic_colorpicker_solar);
        map.put(R.drawable.pin,                       R.drawable.bot_location_solar);
        map.put(R.drawable.profile_discuss,           R.drawable.profile_discuss_solar);
        map.put(R.drawable.profile_phone,             R.drawable.profile_phone_solar);
        map.put(R.drawable.profile_video,             R.drawable.profile_video_solar);
        map.put(R.drawable.qr_flashlight,             R.drawable.qr_flashlight_solar);
        map.put(R.drawable.reactionbutton,            R.drawable.msg_reactions_solar);
        map.put(R.drawable.screencast_big,            R.drawable.screencast_big_solar);
        map.put(R.drawable.search_files_filled,       R.drawable.msg_round_file_solar);
        map.put(R.drawable.share,                     R.drawable.msg_filled_shareout_solar);
        map.put(R.drawable.share_arrow,               R.drawable.share_arrow_solar);
        map.put(R.drawable.smallanimationpin,         R.drawable.smallanimationpin_solar);
        map.put(R.drawable.smiles_inputsearch,        R.drawable.smiles_inputsearch_solar);
        map.put(R.drawable.smiles_tab_clear,          R.drawable.smiles_tab_clear_solar);
        map.put(R.drawable.smiles_tab_gif,            R.drawable.msg_gif_solar);
        map.put(R.drawable.smiles_tab_settings,       R.drawable.smiles_tab_settings_solar);
        map.put(R.drawable.smiles_tab_smiles,         R.drawable.input_smile_solar);
        map.put(R.drawable.smiles_tab_stickers,       R.drawable.msg_sticker_solar);
        map.put(R.drawable.stickers_empty,            R.drawable.stickers_empty_solar);
        map.put(R.drawable.stickers_favorites,        R.drawable.stickers_favorites_solar);
        map.put(R.drawable.stickers_gifs_trending,    R.drawable.stickers_gifs_trending_solar);
        map.put(R.drawable.stickers_recent,           R.drawable.msg_emoji_recent_solar);
        map.put(R.drawable.tabs_reorder,              R.drawable.tabs_reorder_solar);
        map.put(R.drawable.theme_picker,              R.drawable.theme_picker_solar);
        map.put(R.drawable.verified_area,             R.drawable.verified_area_solar);
        map.put(R.drawable.verified_check,            R.drawable.verified_check_solar);
        map.put(R.drawable.verified_profile,          R.drawable.verified_profile_solar);
        map.put(R.drawable.action_share,              R.drawable.share_solar);
        map.put(R.drawable.gift,                      R.drawable.gift_solar);
        map.put(R.drawable.leave,                     R.drawable.leave_solar);
        map.put(R.drawable.live_stream,               R.drawable.live_stream_solar);
        map.put(R.drawable.report,                    R.drawable.report_solar);
        map.put(R.drawable.filled_profile_message_24, R.drawable.message_solar);
        map.put(R.drawable.filled_profile_mute_24,    R.drawable.mute_solar);
        map.put(R.drawable.filled_profile_unmute_24,  R.drawable.unmute_solar);
        map.put(R.drawable.filled_profile_call_24,    R.drawable.call_solar);
        map.put(R.drawable.filled_profile_video_24,   R.drawable.video_solar);
        map.put(R.drawable.filled_profile_member_24,  R.drawable.join_solar);
        map.put(R.drawable.filled_profile_story,      R.drawable.story_solar);
        map.put(R.drawable.filled_profile_stop_24,    R.drawable.block_solar);
        map.put(R.drawable.filled_profile_photo,      R.drawable.camera_solar);
        map.put(R.drawable.filled_profile_edit_24,    R.drawable.group_edit_profile_solar);
        map.put(R.drawable.popup_fixed_alert,         R.drawable.popup_fixed_alert4);
        map.put(R.drawable.popup_fixed_alert2,        R.drawable.popup_fixed_alert4);
        map.put(R.drawable.popup_fixed_alert3,        R.drawable.popup_fixed_alert4);
        map.put(R.drawable.filled_profile_settings,   R.drawable.filled_profile_settings_solar);
        map.put(R.drawable.outline_profile_settings,  R.drawable.msg_settings_solar);
        map.put(R.drawable.tabs_chats_24,             R.drawable.tabs_chats_solar);
        map.put(R.drawable.tabs_chats_active_24,      R.drawable.tabs_chats_active_solar);
        map.put(R.drawable.tabs_contacts_24,          R.drawable.msg_openprofile_solar);
        map.put(R.drawable.tabs_contact_active_24,    R.drawable.tabs_contacts_active_solar);
        map.put(R.drawable.tabs_calls_24,             R.drawable.msg_calls_solar);
        map.put(R.drawable.tabs_calls_active_24,      R.drawable.tabs_calls_active_solar);
        map.put(R.drawable.ic_feed,                   R.drawable.ic_feed_solar);
        map.put(R.drawable.ic_feed_filled,            R.drawable.ic_feed_filled_solar);

        // ---- наши подмены, которых нет в exteraGram (наследие NagramX/AyuGram) ----
        map.put(R.drawable.ayu_ghost,           R.drawable.ayu_ghost_solar);
        map.put(R.drawable.ic_select_between,   R.drawable.msg_select_between_solar);
        map.put(R.drawable.ic_translate,        R.drawable.msg_translate_solar);
        map.put(R.drawable.ic_upward,           R.drawable.ic_upward_solar);
        map.put(R.drawable.input_sticker,       R.drawable.msg_sticker_solar);
        map.put(R.drawable.menu_recent,         R.drawable.msg_recent_solar);
        map.put(R.drawable.menu_saved,          R.drawable.msg_saved_solar);
        map.put(R.drawable.msg_forward_noquote, R.drawable.msg_share_solar);
        map.put(R.drawable.profile_newmsg,      R.drawable.profile_newmsg_filled_solar);
        map.put(R.drawable.qr_gallery,          R.drawable.qr_gallery_solar);
        return map;
    }
}
