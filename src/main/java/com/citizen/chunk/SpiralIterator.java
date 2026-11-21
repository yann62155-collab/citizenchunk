package com.citizen.chunk;

import com.google.gson.JsonObject;

/**
 * Spirale carrée partant du centre : les zones proches du spawn sont générées en premier.
 * Après (2r+1)² positions, le carré complet de rayon r (en chunks) est couvert.
 * L'état tient en quelques entiers : il est sauvegardé pour reprendre après un redémarrage.
 */
public final class SpiralIterator {
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    private int x;
    private int z;
    private int dir;
    private int legLength = 1;
    private int stepInLeg;
    private int legsAtLength;

    /** Renvoie la position relative courante puis avance. */
    public int[] next() {
        int[] current = {x, z};
        x += DX[dir];
        z += DZ[dir];
        stepInLeg++;
        if (stepInLeg == legLength) {
            stepInLeg = 0;
            dir = (dir + 1) & 3;
            legsAtLength++;
            if (legsAtLength == 2) {
                legsAtLength = 0;
                legLength++;
            }
        }
        return current;
    }

    public JsonObject save() {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("dir", dir);
        o.addProperty("legLength", legLength);
        o.addProperty("stepInLeg", stepInLeg);
        o.addProperty("legsAtLength", legsAtLength);
        return o;
    }

    public static SpiralIterator load(JsonObject o) {
        SpiralIterator it = new SpiralIterator();
        it.x = o.get("x").getAsInt();
        it.z = o.get("z").getAsInt();
        it.dir = o.get("dir").getAsInt();
        it.legLength = o.get("legLength").getAsInt();
        it.stepInLeg = o.get("stepInLeg").getAsInt();
        it.legsAtLength = o.get("legsAtLength").getAsInt();
        return it;
    }
}
