f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "rangeeMacros" in s:
    print("DEJA present"); raise SystemExit

# ancre : la fermeture du bloc mouvements (le </LinearLayout> qui suit btnMouvRevel)
anc = '''                android:text="\\u2922" android:textSize="16sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
        </LinearLayout>'''
add = '''                android:text="\\u2922" android:textSize="16sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
        </LinearLayout>
        <HorizontalScrollView android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginTop="4dp" android:scrollbars="none">
            <LinearLayout android:id="@+id/rangeeMacros" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:orientation="horizontal" />
        </HorizontalScrollView>'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Brique 2 (rangee macros) OK :", "rangeeMacros" in s)
else:
    print("ANCRE NON TROUVEE")