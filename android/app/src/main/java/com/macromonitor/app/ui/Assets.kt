package com.macromonitor.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.macromonitor.app.R
import com.macromonitor.app.ui.theme.NectarComforting
import com.macromonitor.app.ui.theme.NectarInvigorating
import com.macromonitor.app.ui.theme.NectarMotivating
import com.macromonitor.app.ui.theme.NectarRefreshing
import com.macromonitor.app.ui.theme.NectarSatisfying

/**
 * Maps bridge-reported field and planter names onto the bundled Bee Swarm
 * Simulator sprites. Returns null when we have no matching artwork so callers
 * can fall back to a text monogram rather than a substitute emoji.
 */
object Assets {

    @DrawableRes val honey: Int = R.drawable.ic_honey
    @DrawableRes val honeyRate: Int = R.drawable.ic_honey_rate
    @DrawableRes val spiritBear: Int = R.drawable.ic_spirit_bear
    @DrawableRes val balloon: Int = R.drawable.ic_balloon

    @DrawableRes
    fun field(field: String?): Int? {
        val key = field?.lowercase() ?: return null
        return when {
            key.contains("sunflower") -> R.drawable.field_sunflower
            key.contains("dandelion") -> R.drawable.field_dandelion
            key.contains("blue flower") -> R.drawable.field_blue_flower
            key.contains("mushroom") -> R.drawable.field_mushroom
            key.contains("clover") -> R.drawable.field_clover
            key.contains("spider") -> R.drawable.field_spider
            key.contains("bamboo") -> R.drawable.field_bamboo
            key.contains("cactus") -> R.drawable.field_cactus
            key.contains("pumpkin") -> R.drawable.field_pumpkin
            key.contains("pineapple") -> R.drawable.field_pineapple
            key.contains("pine tree") -> R.drawable.field_pine_tree
            key.contains("mountain top") -> R.drawable.field_mountain_top
            key.contains("coconut") -> R.drawable.field_coconut
            key.contains("pepper") -> R.drawable.field_pepper
            key.contains("stump") -> R.drawable.field_stump
            key.contains("rose") -> R.drawable.field_rose
            key.contains("strawberry") -> R.drawable.field_strawberry
            else -> null
        }
    }

    /** Mobs / bosses the macro can be fighting (reported as the current activity). */
    @DrawableRes
    fun mob(name: String?): Int? {
        val key = name?.lowercase() ?: return null
        return when {
            key.contains("mondo") -> R.drawable.mob_mondo
            key.contains("ladybug") -> R.drawable.mob_ladybug
            key.contains("rhino") -> R.drawable.mob_rhino_beetle
            key.contains("scorpion") -> R.drawable.mob_scorpion
            key.contains("mantis") -> R.drawable.mob_mantis
            key.contains("werewolf") -> R.drawable.mob_werewolf
            key.contains("king beetle") -> R.drawable.mob_king_beetle
            key.contains("tunnel bear") -> R.drawable.mob_tunnel_bear
            key.contains("stump snail") -> R.drawable.mob_stump_snail
            key.contains("coconut crab") -> R.drawable.mob_coconut_crab
            key.contains("commando") -> R.drawable.mob_commando_chick
            key.contains("vicious") -> R.drawable.mob_vicious_bee
            key.contains("cave monster") -> R.drawable.mob_cave_monster
            else -> null
        }
    }

    /** Field boosters (Blue / Red). */
    @DrawableRes
    fun booster(name: String?): Int? {
        val key = name?.lowercase()?.replace(" ", "") ?: return null
        return when {
            key.contains("bluefieldbooster") || key == "bluebooster" -> R.drawable.booster_blue
            key.contains("redfieldbooster") || key == "redbooster" -> R.drawable.booster_red
            else -> null
        }
    }

    /** Anything the macro can be doing right now: a field, a mob, or a booster. */
    @DrawableRes
    fun activity(name: String?): Int? = field(name) ?: mob(name) ?: booster(name)

    /**
     * Like [activity], but also recognises hive actions that have no field/mob
     * sprite — e.g. "Converting" (the balloon) — so the activity card keeps an
     * illustration instead of falling back to a letter monogram.
     */
    @DrawableRes
    fun activityIcon(name: String?): Int? {
        val key = name?.lowercase() ?: return null
        return when {
            key.contains("convert") || key.contains("balloon") -> balloon
            else -> activity(name)
        }
    }

    @DrawableRes
    fun planter(name: String?): Int? {
        // Natro stores compact camel-case ids (e.g. "BlueClayPlanter"), so match
        // on a space/dash-free key.
        val key = name?.lowercase()?.replace("-", "")?.replace(" ", "") ?: return null
        return when {
            key.contains("planterofplenty") -> R.drawable.planter_of_plenty
            key.contains("glistering") -> R.drawable.planter_glistering
            key.contains("hydroponic") -> R.drawable.planter_hydroponic
            key.contains("heattreated") -> R.drawable.planter_heat_treated
            key.contains("blueclay") -> R.drawable.planter_blue_clay
            key.contains("redclay") -> R.drawable.planter_red_clay
            key.contains("pesticide") -> R.drawable.planter_pesticide
            key.contains("plastic") -> R.drawable.planter_plastic
            key.contains("candy") -> R.drawable.planter_candy
            key.contains("tacky") -> R.drawable.planter_tacky
            key.contains("petal") -> R.drawable.planter_petal
            key.contains("paper") -> R.drawable.planter_paper
            key.contains("festive") -> R.drawable.planter_festive
            key.contains("sticker") -> R.drawable.planter_sticker
            key.contains("ticket") -> R.drawable.planter_ticket
            else -> null
        }
    }

    /** Bar colour for one of BSS's five nectars (matches Natro's report hues). */
    fun nectarColor(name: String?): Color {
        val key = name?.lowercase() ?: return NectarComforting
        return when {
            key.startsWith("comfort") -> NectarComforting
            key.startsWith("motiv") -> NectarMotivating
            key.startsWith("satisf") -> NectarSatisfying
            key.startsWith("refresh") -> NectarRefreshing
            key.startsWith("invig") -> NectarInvigorating
            else -> NectarComforting
        }
    }
}
