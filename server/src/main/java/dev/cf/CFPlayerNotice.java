package dev.cf;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;

/** Normal Castle Fight feedback never pollutes player chat. Console retains full diagnostics. */
final class CFPlayerNotice {
    private CFPlayerNotice() { }
    static void send(CommandSender sender, String message) {
        if (sender instanceof Player player) send(player,message);
        else if (message!=null) sender.sendMessage(message);
    }
    static void send(Player player, String message) {
        if (message==null || message.isBlank())return;
        String line=message.split("\\R",2)[0].trim()
                .replaceFirst("^\\[(?:CF|КФ)[^]]*]\\s*", "");
        if(line.length()>112)line=line.substring(0,109)+"...";
        player.sendActionBar(Component.text(line,NamedTextColor.GOLD));
    }
}
