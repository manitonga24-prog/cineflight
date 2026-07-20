from cine_perception import analyser_photo
from cine_perception_reel import charger_modeles, _yolo_reel
import numpy as np, json
from PIL import Image

seg, yolo, sam = charger_modeles(
    chemin_sam2_checkpoint="sam2.1_hiera_large.pt",
    config_sam2="configs/sam2.1/sam2.1_hiera_l.yaml",
    modele_yolo="yolov8x.pt",
)

img = np.array(Image.open("phare.jpg").convert("RGB"))
res = analyser_photo(img, seg, yolo, sam)
print(json.dumps(res.to_dict(), indent=2, ensure_ascii=False))

print("\n--- Detections YOLO brutes ---")
boites = _yolo_reel(img)
if not boites:
    print("  (aucune detection)")
for b in boites:
    print(f"  {b.classe}: conf={b.conf:.2f} boite=({b.x0},{b.y0},{b.x1},{b.y1})")
