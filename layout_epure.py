f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
layout = """<?xml version="1.0" encoding="utf-8"?>
<!-- CineFlight Solo - interface epuree (petites icones, vue degagee) -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <SurfaceView android:id="@+id/surfaceFlux"
        android:layout_width="match_parent" android:layout_height="match_parent" />

    <ca.cineflight.stage.control.OverlayYolo android:id="@+id/overlayYolo"
        android:layout_width="match_parent" android:layout_height="match_parent" />

    <!-- HAUT : mini-telemetrie a gauche, capture a droite -->
    <LinearLayout
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_gravity="top" android:orientation="horizontal"
        android:padding="6dp" android:gravity="center_vertical"
        android:background="#66000000">

        <TextView android:id="@+id/txtBatterie"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginEnd="10dp" android:text="\\uD83D\\uDD0B —"
            android:textColor="#FFFFFF" android:textSize="13sp" android:fontFamily="monospace" />
        <TextView android:id="@+id/txtAltitude"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginEnd="10dp" android:text="\\u2195 —"
            android:textColor="#FFFFFF" android:textSize="13sp" android:fontFamily="monospace" />
        <TextView android:id="@+id/txtDistance"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginEnd="10dp" android:text="\\u27A4 —"
            android:textColor="#FFFFFF" android:textSize="13sp" android:fontFamily="monospace" />
        <TextView android:id="@+id/txtSujet"
            android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_weight="1" android:gravity="center"
            android:text="\\u25CF" android:textColor="#C62828"
            android:textSize="14sp" android:textStyle="bold" android:fontFamily="monospace" />
        <Button android:id="@+id/btnPhoto"
            android:layout_width="44dp" android:layout_height="40dp"
            android:layout_marginEnd="4dp" android:padding="0dp"
            android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\uD83D\\uDCF7" android:textSize="16sp" android:backgroundTint="#37474F" />
        <Button android:id="@+id/btnRec"
            android:layout_width="44dp" android:layout_height="40dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\u23FA" android:textSize="16sp"
            android:textColor="#FFFFFF" android:backgroundTint="#37474F" />
    </LinearLayout>

    <!-- champs telemetrie caches (gardes pour le code, non affiches) -->
    <LinearLayout android:layout_width="0dp" android:layout_height="0dp"
        android:visibility="gone" android:orientation="vertical">
        <TextView android:id="@+id/txtVitesse" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/txtGps" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/txtCap" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/txtSignalRc" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/txtSignalVideo" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/texteEtat" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/texteStats" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <Button android:id="@+id/btnRtsp" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <Button android:id="@+id/btnGimbalUp" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <Button android:id="@+id/btnGimbalDown" android:layout_width="wrap_content" android:layout_height="wrap_content" />
    </LinearLayout>

    <TextView android:id="@+id/txtAlerte"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal" android:layout_marginTop="44dp"
        android:padding="8dp" android:text="" android:textColor="#FFFFFF"
        android:textSize="16sp" android:textStyle="bold" android:fontFamily="monospace"
        android:visibility="gone" android:background="#CCB71C1C" />

    <!-- BAS GAUCHE : PLAN + MOUVEMENT en mini-icones -->
    <LinearLayout
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_gravity="bottom|start" android:orientation="vertical"
        android:layout_margin="8dp" android:padding="4dp" android:background="#66000000">

        <LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:orientation="horizontal" android:layout_marginBottom="4dp">
            <Button android:id="@+id/btnPlanGros" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="GP" android:textSize="11sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnPlanAmericain" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="AM" android:textSize="11sp" android:backgroundTint="#2E7D32" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnPlanPied" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="PD" android:textSize="11sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnPlanEnsemble" android:layout_width="40dp" android:layout_height="36dp"
                android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="ENS" android:textSize="10sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
        </LinearLayout>

        <LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:orientation="horizontal">
            <Button android:id="@+id/btnMouvStatique" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u25A3" android:textSize="16sp" android:backgroundTint="#2E7D32" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnMouvOrbite" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u25CE" android:textSize="16sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnMouvTravel" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u2194" android:textSize="16sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnMouvRevel" android:layout_width="40dp" android:layout_height="36dp"
                android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u2922" android:textSize="16sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
        </LinearLayout>
    </LinearLayout>

    <!-- BAS CENTRE : SUIVRE (bouton cle) -->
    <Button android:id="@+id/btnMode"
        android:layout_width="wrap_content" android:layout_height="48dp"
        android:layout_gravity="bottom|center_horizontal" android:layout_marginBottom="10dp"
        android:paddingStart="24dp" android:paddingEnd="24dp"
        android:text="SUIVRE" android:textSize="15sp" android:textStyle="bold"
        android:textColor="#FFFFFF" android:backgroundTint="#2E7D32" />

    <!-- BAS DROITE : vol en mini-icones -->
    <LinearLayout
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_gravity="bottom|end" android:orientation="horizontal"
        android:layout_margin="8dp" android:padding="4dp" android:background="#66000000">
        <Button android:id="@+id/btnDecoller" android:layout_width="44dp" android:layout_height="44dp"
            android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\uD83D\\uDEEB" android:textSize="18sp" />
        <Button android:id="@+id/btnAtterrir" android:layout_width="44dp" android:layout_height="44dp"
            android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\uD83D\\uDEEC" android:textSize="18sp" />
        <Button android:id="@+id/btnRth" android:layout_width="44dp" android:layout_height="44dp"
            android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\uD83C\\uDFE0" android:textSize="16sp" android:backgroundTint="#F9A825" android:textColor="#000000" />
        <Button android:id="@+id/btnUrgence" android:layout_width="44dp" android:layout_height="44dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\u26D4" android:textSize="18sp" android:textColor="#FFFFFF" android:backgroundTint="#C62828" />
    </LinearLayout>

</FrameLayout>
"""
open(f, "w", encoding="utf-8", newline="\n").write(layout)
print("Layout epure ecrit. IDs preserves.")