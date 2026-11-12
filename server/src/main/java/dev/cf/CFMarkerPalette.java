package dev.cf;

import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import java.util.*;

/*
 * ============================================================================
 *          НЕПРИКОСНОВЕННОЕ ПРАВИЛО АВТОРА CASTLE FIGHT — ЧИТАТЬ ВСЕМ!
 * ============================================================================
 *   CF MAP 3.0 — ЕДИНСТВЕННЫЙ ФОРМАТ. СТАРЫХ КАРТ, СХЕМ И ПАЛИТР НЕТ.
 *
 *   ВСЕ 16 ЦВЕТОВ КОВРОВ ИСПОЛЬЗУЮТСЯ ИСКЛЮЧИТЕЛЬНО ДЛЯ РАЗМЕТКИ БАЗ
 *   И СТРОИТЕЛЬСТВА БАШЕН. НИ ОДИН КОВЁР НЕ ЯВЛЯЕТСЯ ПУТЕВОЙ ТОЧКОЙ.
 *
 *   КАЖДЫЙ ЦВЕТ ЖЁСТКО ПРИВЯЗАН К (КОМАНДА, НОМЕР ДОРОЖКИ).
 *   Для каждой команды/дорожки разрешены два АЛЬТЕРНАТИВНЫХ цвета,
 *   но все ДЕВЯТЬ ковров одной площадки 3x3 ОБЯЗАНЫ БЫТЬ ОДНОГО ЦВЕТА.
 *   НЕЛЬЗЯ объединять дорожки по команде или выбирать ближайшую дорогу.
 *
 *   МАРШРУТЫ ЗАДАЮТСЯ ТОЛЬКО МАЯКАМИ ПРЯМО НА ДОРОГЕ:
 *       [цветное стекло]       <- цвет кодирует номер дорожки
 *       [магнетит / LODESTONE] <- установлен ПОВЕРХ дорожного покрытия
 *       [блок дороги]         <- сплошной блок, на котором стоят мобы
 *   НЕ прятать маяки под пол! Два блока маяка убираются только В ПАМЯТИ
 *   игрового мира. Файлы Minecraft карты никогда не перезаписываются.
 *
 *   ВНИМАНИЕ БУДУЩИМ РАЗРАБОТЧИКАМ И ИИ:
 *   НЕ ВОЗВРАЩАТЬ ROAD_CARPET, LEGACY, SCHEMA=2.x, ЦВЕТА "ТОЛЬКО ПО КОМАНДЕ"
 *   ИЛИ ОПРЕДЕЛЕНИЕ ДОРОЖКИ ПО РАССТОЯНИЮ. ЭТО ПРЯМОЙ ЗАПРЕТ АВТОРА.
 *   Полный контракт: docs/MAP_CONTRACT.md.
 * ============================================================================
 */
final class CFMarkerPalette {
    static final int MAX_LANES = 4;
    record Mark(int lane, String team, String role, Block block, Material item) { }

    // Единственный формат 3.0: 16 ковров = 16 строительных цветов; 8 пар team/lane.
    // Два цвета для одной пары — АЛЬТЕРНАТИВЫ, а НЕ смешиваемые клетки 3x3.
    // Для каждой пары team/lane два строго одноцветных варианта.
    static final List<Mark> ALL = List.of(
        new Mark(1,"blue","BLUE_1",Block.CYAN_CARPET,Material.CYAN_CARPET),
        new Mark(1,"blue","BLUE_1_ALT",Block.WHITE_CARPET,Material.WHITE_CARPET),
        new Mark(1,"red","RED_1",Block.PURPLE_CARPET,Material.PURPLE_CARPET),
        new Mark(1,"red","RED_1_ALT",Block.BROWN_CARPET,Material.BROWN_CARPET),
        new Mark(2,"blue","BLUE_2",Block.LIGHT_BLUE_CARPET,Material.LIGHT_BLUE_CARPET),
        new Mark(2,"blue","BLUE_2_ALT",Block.LIGHT_GRAY_CARPET,Material.LIGHT_GRAY_CARPET),
        new Mark(2,"red","RED_2",Block.MAGENTA_CARPET,Material.MAGENTA_CARPET),
        new Mark(2,"red","RED_2_ALT",Block.RED_CARPET,Material.RED_CARPET),
        new Mark(3,"blue","BLUE_3",Block.BLUE_CARPET,Material.BLUE_CARPET),
        new Mark(3,"blue","BLUE_3_ALT",Block.GRAY_CARPET,Material.GRAY_CARPET),
        new Mark(3,"red","RED_3",Block.PINK_CARPET,Material.PINK_CARPET),
        new Mark(3,"red","RED_3_ALT",Block.ORANGE_CARPET,Material.ORANGE_CARPET),
        new Mark(4,"blue","BLUE_4",Block.GREEN_CARPET,Material.GREEN_CARPET),
        new Mark(4,"blue","BLUE_4_ALT",Block.BLACK_CARPET,Material.BLACK_CARPET),
        new Mark(4,"red","RED_4",Block.LIME_CARPET,Material.LIME_CARPET),
        new Mark(4,"red","RED_4_ALT",Block.YELLOW_CARPET,Material.YELLOW_CARPET));

    private static final Map<Integer,Mark> BY_STATE = index(ALL);
    private static Map<Integer,Mark> index(List<Mark> marks) {
        var out = new HashMap<Integer,Mark>();
        for (var mark : marks)
            if (out.put(mark.block().stateId(),mark)!=null)
                throw new IllegalStateException("Повторный цвет ковра: "+mark.block().name());
        return Map.copyOf(out);
    }
    static Mark of(Block block) { return BY_STATE.get(block.stateId()); }
    static Mark of(int state) { return BY_STATE.get(state); }
    private CFMarkerPalette() { }
}
