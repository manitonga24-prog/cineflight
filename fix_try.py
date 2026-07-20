f = r"C:\cineflight_android\CineFlightSolo\app\src\main\jni\yolo11ncnn.cpp"
s = open(f, encoding="utf-8").read()
old = "    try { g_aruco->detectMarkers(gris, corners, ids, rejected); } catch (...) { ids.clear(); }"
new = "    g_aruco->detectMarkers(gris, corners, ids, rejected);"
if old in s:
    s = s.replace(old, new, 1)
    print("try/catch retire")
else:
    print("ANCRE NON TROUVEE")
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("try present encore :", "try {" in s and "detectMarkers" in s)