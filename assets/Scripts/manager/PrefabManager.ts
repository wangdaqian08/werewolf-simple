import {_decorator, Prefab, resources} from "cc";

const { ccclass, property } = _decorator;

@ccclass("PrefabManager")
export class PrefabManager {
  private static resourcesMap = {};

  public static loadPrefab(callback) {
    resources.loadDir("Prefabs", (err, assets: Prefab[]) => {
      if (err) {
        console.error("Error loading prefabs:", err);
        callback(false);
        return;
      }

      assets.forEach((asset) => {
        this.resourcesMap[asset.name] = asset;
      });
      callback(true);
      console.log("Prefabs loaded successfully:", this.resourcesMap);
    });
  }

  public static getPrefab(name: string): Prefab | null {
    if (this.resourcesMap[name]) {
      return this.resourcesMap[name];
    } else {
      console.warn(`Prefab with name ${name} not found.`);
      return null;
    }
  }
}
