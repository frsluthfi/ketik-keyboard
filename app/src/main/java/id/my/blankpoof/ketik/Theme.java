package id.my.blankpoof.ketik;

import android.content.Context;
import android.content.res.Configuration;

/** Colour palette for light and dark mode. */
final class Theme {
    final boolean dark;
    final int background, keyboardBg, toolbarBg, key, keyPressed, funcKey, funcKeyPressed;
    final int text, hintText, accent, accentPressed, accentText;
    final int icon, iconActive, divider;
    final int popupBg, popupText, popupSelected, popupSelectedText;
    final int shadow, trail, card, cardSelectedStroke, toolCircle;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = 0xFF121316;   // toolbar row and panels
            keyboardBg = 0xFF000000;   // behind the keys: pure black
            toolbarBg = 0xFF121316;
            key = 0xFF2A2C30;
            keyPressed = 0xFF46494F;
            funcKey = 0xFF1E2024;
            funcKeyPressed = 0xFF383B41;
            text = 0xFFE9EBEE;
            hintText = 0xFF8E949C;
            accent = 0xFF4F86F0;
            accentPressed = 0xFF3A6CCB;
            accentText = 0xFFFFFFFF;
            icon = 0xFFC4C8CE;
            iconActive = 0xFF6F9BF3;
            divider = 0x26FFFFFF;
            popupBg = 0xFF3A3D43;
            popupText = 0xFFF1F2F4;
            popupSelected = 0xFF4F86F0;
            popupSelectedText = 0xFFFFFFFF;
            shadow = 0x66000000;
            trail = 0xCC6F9BF3;
            card = 0xFF24262A;
            toolCircle = 0xFF22242A;
            cardSelectedStroke = 0xFF6F9BF3;
        } else {
            background = 0xFFEDEFF2;
            keyboardBg = 0xFFEDEFF2;
            toolbarBg = 0xFFF6F7F9;    // tool row: lighter than the keys area, not pure white
            key = 0xFFFFFFFF;
            keyPressed = 0xFFD5D9DF;
            funcKey = 0xFFDADEE4;
            funcKeyPressed = 0xFFC2C7CF;
            text = 0xFF1E2023;
            hintText = 0xFF878C94;
            accent = 0xFF2F6BE4;
            accentPressed = 0xFF2456BB;
            accentText = 0xFFFFFFFF;
            icon = 0xFF4B5058;
            iconActive = 0xFF2F6BE4;
            divider = 0x1F000000;
            popupBg = 0xFFFFFFFF;
            popupText = 0xFF1E2023;
            popupSelected = 0xFF2F6BE4;
            popupSelectedText = 0xFFFFFFFF;
            shadow = 0x22000000;
            trail = 0xBB2F6BE4;
            card = 0xFFFFFFFF;
            toolCircle = 0xFFE7E9EE;
            cardSelectedStroke = 0xFF2F6BE4;
        }
    }

    static Theme of(Context c) {
        int mode = Prefs.integer(c, Prefs.THEME);
        boolean night = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new Theme(mode == 2 || (mode == 0 && night));
    }
}
