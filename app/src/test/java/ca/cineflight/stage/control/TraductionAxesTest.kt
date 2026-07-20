package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Tests de TraductionAxes.versDji : conversion d'une commande SCENE (repere fixe,
 * OpenVR) en commande Virtual Stick DJI (repere du DRONE, qui tourne).
 *
 * C'est le coeur "delicat" de l'app : une erreur ici et le drone part de travers,
 * ou pire, l'altitude (vy) se melange a l'horizontale (le piege Y/Z documente).
 * Comme tous les tests de PiloteDrone utilisaient cap=0 (identite) pour eviter la
 * rotation, ce fichier couvre justement le cas cap != 0, jamais teste ailleurs.
 *
 * Rappels du contrat (repere drone) :
 *   pitch   = vz*cos(cap) + vx*sin(cap)     (avant/arriere du drone)
 *   roll    = -vz*sin(cap) + vx*cos(cap)    (droite/gauche du drone)
 *   throttle= vy                            (altitude, JAMAIS melangee)
 *   yaw     = yawRate                       (rotation, passe tel quel)
 * => (roll, pitch) = Rot(cap) . (vx, vz) : une rotation pure, donc la NORME
 *    horizontale est conservee.
 */
class TraductionAxesTest {

    private val T = 1e-3   // tolerance m/s (entrees bornees a +-5 dans les cas cibles)

    // ---------------------------------------------- cap = 0 : identite documentee
    @Test fun cap_zero_est_identite() {
        val c = TraductionAxes.versDji(vx = 1f, vy = 2f, vz = 3f, yawRate = 4f, capDroneDeg = 0f)
        assertEquals(3.0, c.pitch.toDouble(), T)             // pitch = vz
        assertEquals(1.0, c.roll.toDouble(), T)              // roll  = vx
        assertEquals(2.0, c.verticalThrottle.toDouble(), T)  // throttle = vy
        assertEquals(4.0, c.yaw.toDouble(), T)               // yaw = yawRate
    }

    // ---------------------------------------------- le piege Y/Z : vy ne bouge jamais
    @Test fun throttle_est_toujours_vy_quel_que_soit_le_cap() {
        for (cap in intArrayOf(0, 37, 90, 133, 180, 270, 359)) {
            val c = TraductionAxes.versDji(vx = 1.5f, vy = 0.7f, vz = -2.2f, yawRate = 12f,
                capDroneDeg = cap.toFloat())
            assertEquals("cap=$cap : vy doit passer tel quel dans throttle",
                0.7, c.verticalThrottle.toDouble(), T)
        }
    }

    @Test fun une_horizontale_pure_ne_cree_aucune_altitude() {
        val c = TraductionAxes.versDji(vx = 2f, vy = 0f, vz = 2f, yawRate = 0f, capDroneDeg = 33f)
        assertEquals("horizontale pure -> throttle nul", 0.0, c.verticalThrottle.toDouble(), T)
    }

    @Test fun yaw_passe_tel_quel() {
        for (cap in intArrayOf(0, 45, 90, 200, 359)) {
            val c = TraductionAxes.versDji(0f, 0f, 0f, yawRate = -17.5f, capDroneDeg = cap.toFloat())
            assertEquals("cap=$cap", -17.5, c.yaw.toDouble(), T)
        }
    }

    // ---------------------------------------------- angles remarquables
    @Test fun cap_90() {
        // cos90=0, sin90=1 : pitch = vx ; roll = -vz
        val c = TraductionAxes.versDji(vx = 1f, vy = 0f, vz = 2f, yawRate = 0f, capDroneDeg = 90f)
        assertEquals(1.0, c.pitch.toDouble(), T)
        assertEquals(-2.0, c.roll.toDouble(), T)
    }

    @Test fun cap_180() {
        // pitch = -vz ; roll = -vx
        val c = TraductionAxes.versDji(vx = 1f, vy = 0f, vz = 2f, yawRate = 0f, capDroneDeg = 180f)
        assertEquals(-2.0, c.pitch.toDouble(), T)
        assertEquals(-1.0, c.roll.toDouble(), T)
    }

    @Test fun cap_270() {
        // cos270=0, sin270=-1 : pitch = -vx ; roll = vz
        val c = TraductionAxes.versDji(vx = 1f, vy = 0f, vz = 2f, yawRate = 0f, capDroneDeg = 270f)
        assertEquals(-1.0, c.pitch.toDouble(), T)
        assertEquals(2.0, c.roll.toDouble(), T)
    }

    // ---------------------------------------------- PROPRIETE : 50 000 rotations aleatoires
    @Test fun proprietes_rotation_50k() {
        val rnd = Random(20260714L)
        val n = 50_000
        for (i in 0 until n) {
            val vx = rnd.nextFloat() * 10f - 5f
            val vy = rnd.nextFloat() * 10f - 5f
            val vz = rnd.nextFloat() * 10f - 5f
            val yawRate = rnd.nextFloat() * 200f - 100f
            val cap = rnd.nextFloat() * 720f - 360f
            val c = TraductionAxes.versDji(vx, vy, vz, yawRate, cap)

            // 1. altitude et rotation passent EXACTEMENT (aucun calcul dessus)
            assertEquals("i=$i throttle==vy", vy, c.verticalThrottle, 0f)
            assertEquals("i=$i yaw==yawRate", yawRate, c.yaw, 0f)
            // 2. la norme horizontale est conservee (c'est une rotation pure)
            val avant = hypot(vx.toDouble(), vz.toDouble())
            val apres = hypot(c.pitch.toDouble(), c.roll.toDouble())
            assertEquals("i=$i norme (cap=$cap)", avant, apres, 1e-2 + avant * 1e-4)
            // 3. valeurs finies
            assertTrue("i=$i fini", c.pitch.isFinite() && c.roll.isFinite())
            // 4. correspondance exacte a la formule (detecte tout echange d'axe)
            val rad = Math.toRadians(cap.toDouble())
            val pitchAtt = vz * cos(rad) + vx * sin(rad)
            val rollAtt = -vz * sin(rad) + vx * cos(rad)
            assertEquals("i=$i pitch", pitchAtt, c.pitch.toDouble(), 1e-2)
            assertEquals("i=$i roll", rollAtt, c.roll.toDouble(), 1e-2)
        }
        println("TRADUCTION-AXES ok : $n rotations verifiees (norme conservee, throttle/yaw pass-through)")
    }
}
