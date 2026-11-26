package online.bingzi.bilibili.video.internal.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.ShadowColor
import taboolib.platform.util.bukkitPlugin

/** 使用同一共享物理图集、调色板与偏移协议；不复制旧字形或改写Lib注册表。 */
internal object GuiArt {
    private lateinit var data: JsonObject
    private lateinit var palette: List<Int>
    private val spacing = Key.key("jj_bilibili_gui_261124:spacing")
    fun initialize() {
        data = bukkitPlugin.getResource("gui-atlas.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        require(data["protocol"].asInt == 2 && data["shift-bias"].asInt == 64)
        palette = data.getAsJsonArray("palette").map { it.asInt }
        city.jiujiu.lib.gui.presentation.SharedFontShader.initialize()
    }
    fun validateColor(value: String) { require(value.matches(Regex("#[0-9A-Fa-f]{6}")) && value.drop(1).toInt(16) in palette) { "颜色不在共享调色板中: $value" } }
    fun hasMissing(value: String, style: String): Boolean = value.any { !data.getAsJsonObject("glyphs").getAsJsonObject(style).has(it.toString()) }
    private fun shift(x: Int): Component { require(x in -512..511); return Component.text((0xf200+x).toChar().toString()).font(spacing) }
    private fun color(value: String, y: Int): TextColor {
        val rgb=value.drop(1).toInt(16);val index=palette.indexOf(rgb)
        require(index >= 0 && y-7 in -64..191)
        return TextColor.color(0xfe0000 or ((y-7+64) shl 8) or index)
    }
    fun base(detail: Boolean): Component {
        var out: Component=Component.empty().decoration(TextDecoration.ITALIC,false).decoration(TextDecoration.BOLD,false).shadowColor(ShadowColor.none())
        for (entry in data.getAsJsonObject("skins").getAsJsonArray(if(detail) "detail" else "main")) {
            val t=entry.asJsonObject;val x=t["x"].asInt
            out=out.append(shift(x)).append(Component.text(t["char"].asInt.toChar().toString())
                .font(Key.key(t["font"].asString)).color(color("#FFFFFF",t["y"].asInt)))
                .append(shift(-x-t["advance"].asInt))
        }
        // 直接引用共享bounds字体：预编码物理字形不会触发Lib二次转换，须显式保留CPU边界。
        val bounds=Key.key(data["bounds-font"].asString)
        return out.append(Component.text("\ue000\uf000\ue001\uf000").font(bounds).color(TextColor.color(0xffffff)))
    }
    fun line(value: String, x: Int, y: Int, maxWidth: Int, style: String, tint: String, center: Boolean=false): Component {
        val glyphs=data.getAsJsonObject("glyphs").getAsJsonObject(style)
        val run=mutableListOf<JsonObject>();var width=0
        for(c in value.take(256)) {
            val g=glyphs[c.toString()]?.asJsonObject ?: glyphs["?"]?.asJsonObject ?: continue
            val step=g["advance"].asInt
            if(width+step>maxWidth) break
            run.add(g);width+=step
        }
        val start=if(center) x-width/2 else x
        var out: Component=Component.empty().append(shift(start))
        for(g in run) {
            if(!g.has("font")) out=out.append(shift(g["advance"].asInt)) else
                out=out.append(Component.text(g["char"].asInt.toChar().toString()).font(Key.key(g["font"].asString)).color(color(tint,y)))
        }
        return out.append(shift(-start-width))
    }
}
