# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "miniCarte" in s:
    print("DEJA present"); raise SystemExit
anc = '''    <ca.cineflight.stage.control.OverlayYolo android:id="@+id/overlayYolo"
        android:layout_width="match_parent" android:layout_height="match_parent" />'''
add = anc + '''
    <!-- MINI-CARTE : bas gauche, cachee par defaut, basculee par le bouton carte -->
    <FrameLayout android:id="@+id/boiteMiniCarte"
        android:layout_width="150dp" android:layout_height="150dp"
        android:layout_gravity="bottom|start" android:layout_margin="8dp"
        android:visibility="gone">
        <org.osmdroid.views.MapView android:id="@+id/miniCarte"
            android:layout_width="match_parent" android:layout_height="match_parent" />
        <Button android:id="@+id/btnAgrandirCarte"
            android:layout_width="28dp" android:layout_height="28dp"
            android:layout_gravity="top|end" android:layout_margin="2dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\u26F6" android:textSize="13sp"
            android:textColor="#FFFFFF" android:backgroundTint="#CC000000" />
    </FrameLayout>'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Mini-carte ajoutee au layout :", "miniCarte" in s)
else:
    print("ANCRE NON TROUVEE")