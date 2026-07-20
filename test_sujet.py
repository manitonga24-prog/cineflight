import sys
from cine_perception import analyser_photo
from cine_perception_reel import charger_modeles, _yolo_reel
import numpy as np, json
from PIL import Image

seg, yolo, sam = charger_modeles(
    chemin_sam2_checkpoint="sam2.1_hiera_large.pt",
    config_sam2="configs/sam2.1/sam2.1_hiera_l.yaml",
    modele_yolo="yolov8x-world.pt",
)
nom = sys.argv[1] if len(sys.argv) > 1 else "phare.jpg"
img = np.array(Image.open(nom).convert("RGB"))
res = analyser_photo(img, seg, yolo, sam)
print(f"=== {nom} ===")
print(json.dumps(res.to_dict(), indent=2, ensure_ascii=False))
print("--- YOLO brut ---")
for b in _yolo_reel(img):
    print(f"  {b.classe}: conf_finale={b.conf:.2f} ({b.x0},{b.y0},{b.x1},{b.y1})")
