# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "txtValeursMouv" in s:
    print("DEJA present"); raise SystemExit
anc = '''            <LinearLayout android:id="@+id/rangeeMacros" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:orientation="horizontal" />
        </HorizontalScrollView>'''
add = anc + '''
        <TextView android:id="@+id/txtValeursMouv" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:layout_marginTop="3dp"
            android:textSize="9sp" android:textColor="#80FFFFFF" android:fontFamily="monospace" android:text="" />'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("txtValeursMouv ajoute :", "txtValeursMouv" in s)
else:
    print("ANCRE NON TROUVEE")