import {_decorator, director} from "cc";
import {Load} from "../Load";
import {PrefabManager} from "./PrefabManager";

const { ccclass, property } = _decorator;

@ccclass("ResLoader")
export class ResLoader {
  public static resourceType = ["prefab"];
  public static completeCount = 0;

  public static init() {
    for (let i = 0; i < this.resourceType.length; i++) {
      if (this.resourceType[i] === "prefab") {
        this.loadPrefab();
      }
    }
  }
  public static loadPrefab() {
    PrefabManager.loadPrefab((success) => {
      if (success) {
        this.completeLoad();
        console.log("Prefab resources loaded successfully.");
      } else {
        console.error("Failed to load prefab resources.");
      }
    });
  }

  private static completeLoad() {
    this.completeCount++;
    if (this.completeCount == this.resourceType.length) {
      director.preloadScene("Game", (err) => {
        if (err) {
          console.error("Error preloading Game scene:", err);
          return;
        }
        Load.instance.loadCompleted = true;
        console.log("Game scene preloaded successfully.");
      });
      // All resources loaded successfully
      console.log("All resources loaded successfully.");
      // You can trigger an event or callback here to notify that all resources are loaded
    }
  }
}
