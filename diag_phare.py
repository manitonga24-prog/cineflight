from cine_perception_reel import charger_modeles
import numpy as np
from PIL import Image

seg, yolo, sam = charger_modeles(
    chemin_sam2_checkpoint="sam2.1_hiera_large.pt",
    config_sam2="configs/sam2.1/sam2.1_hiera_l.yaml",
    modele_yolo="yolov8x-world.pt",
)
from cine_perception_reel import _M
img = np.array(Image.open("phare.jpg").convert("RGB"))

# detection BRUTE sans filtre (conf tres bas)
results = _M.yolo(img, conf=0.001, verbose=False)
print("=== Detections YOLO-World brutes (conf>=0.001) ===")
for r in results:
    if r.boxes is None: continue
    for b in r.boxes:
        conf = float(b.conf[0]); cls_id = int(b.cls[0])
        nom = _M.yolo.names.get(cls_id, str(cls_id))
        x0,y0,x1,y1 = b.xyxy[0].tolist()
        print(f"  {nom}: conf={conf:.4f} ({int(x0)},{int(y0)},{int(x1)},{int(y1)})")
print("(si vide : YOLO-World ne voit AUCUN concept du vocabulaire dans cette image)")
