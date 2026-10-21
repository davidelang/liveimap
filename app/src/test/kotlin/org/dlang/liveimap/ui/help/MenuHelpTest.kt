package org.dlang.liveimap.ui.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MenuHelpTest {
    @Test
    fun menuRowsUseHelpAndStrings() {
        val screens = listOf(
            "main/kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt",
            "main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt",
            "main/kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt",
            "main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
            "main/kotlin/org/dlang/liveimap/ui/search/AdvancedSearchScreen.kt",
        )
        for (screen in screens) {
            val bare = src(screen).replace("HelpDropdownMenuItem(", "")
            assertFalse(screen, bare.contains("DropdownMenuItem("))
        }
        val help = src("main/kotlin/org/dlang/liveimap/ui/help/IconHelp.kt")
        assertTrue(help.contains("fun HelpDropdownMenuItem"))
        assertTrue(help.contains("onLongPress"))
        assertTrue(help.contains("IconHelpDialog"))
        assertTrue(src("main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt").contains("DropdownMenuItem("))
        val strings = src("main/res/values/strings.xml")
        for (name in helpMenuNames) {
            assertTrue(name, strings.contains("""name="$name""""))
        }
    }

    private fun src(path: String) =
        listOf("src/$path", "app/src/$path").map(::File).first { it.exists() }.readText()
}

private val helpMenuNames = listOf(
    "help_menu_folders_refresh",
    "help_menu_folders_collapse_all",
    "help_menu_folders_save_default",
    "help_menu_folders_reset_default",
    "help_menu_folders_unsent",
    "help_menu_toolbar_customize",
    "help_menu_folders_always_expand",
    "help_menu_folders_dont_always_expand",
    "help_menu_drawer_settings",
    "help_menu_help_title",
    "help_menu_about_title",
    "help_menu_index_select_all",
    "help_menu_index_mark_answered",
    "help_menu_index_mark_unanswered",
    "help_menu_index_undelete",
    "help_menu_index_bounce",
    "help_menu_index_clear_selection",
    "help_menu_label_open_at",
    "help_menu_index_expunge_menu",
    "help_menu_index_folder_info",
    "help_menu_index_jump",
    "help_menu_index_next_unread",
    "help_menu_index_mark_all_read",
    "help_menu_index_search_none",
    "help_menu_index_refresh",
    "help_menu_index_search",
    "help_menu_index_filter",
    "help_menu_index_show_new",
    "help_menu_index_show_not_deleted",
    "help_menu_index_show_thread",
    "help_menu_reader_refresh",
    "help_menu_reader_save",
    "help_menu_reader_take_address",
    "help_menu_reader_filter_like",
    "help_menu_reader_dark_colors",
    "help_menu_reader_original_colors",
    "help_menu_reader_images_sender_off",
    "help_menu_reader_images_sender_on",
    "help_menu_reader_images_list_off",
    "help_menu_reader_images_list_on",
    "help_menu_compose_reply",
    "help_menu_compose_reply_all",
    "help_menu_compose_forward",
    "help_menu_compose_postpone",
    "help_menu_compose_from_by_rules",
    "help_menu_compose_bounce",
    "help_menu_drawer_delete",
    "help_menu_label_move",
    "help_menu_label_spam",
    "help_menu_reader_all_headers",
    "help_menu_favorite",
    "help_menu_delete_policy",
    "help_menu_forward_style",
    "help_menu_body_view",
    "help_menu_from_address",
    "help_menu_saved_search",
    "help_menu_search_field",
    "help_menu_simple_search",
    "help_menu_sort",
    "help_menu_direction",
    "help_menu_filter",
    "help_menu_seen",
    "help_menu_flag",
    "help_menu_move",
    "about_lib_etpan",
    "about_libfastjson",
    "about_openssl",
    "about_cyrus_sasl",
    "toolbar_rows_fewer",
    "toolbar_rows_more",
)
