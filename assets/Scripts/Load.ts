import {_decorator, Component, director, Label, Sprite, UITransform,} from "cc";
import {ResLoader} from "./manager/ResLoader";

const { ccclass, property } = _decorator;

@ccclass("Load")
export class Load extends Component {
  @property(Sprite)
  bar: Sprite = null;
  @property(Label)
  version: Label = null;
  @property(Label)
  desc: Label = null;
  public loadCompleted: boolean = false;
  private progress: number = 0;
  private frameComplete: boolean = false;

  public static instance: Load;

  protected onLoad(): void {
    Load.instance = this;
    this.version.string = "v_" + "0.0.1";
    this.scheduleOnce(() => {
      this.startLoad();
    }, 0.5);
  }

  startLoad() {
    ResLoader.init();
  }

  protected update(dt: number) {
    this.progress += 0.5;
    if (this.progress >= 98) {
      this.progress = 98;
      this.frameComplete = true;
    }

    if (this.progress < 10) {
      this.progress = 10;
    }

    this.bar.node.getComponent(UITransform).width = (400 * this.progress) / 100;
    this.desc.string = "Loading... " + Math.floor(this.progress) + "%";
    if (this.frameComplete && this.progress > 90 && this.loadCompleted) {
      director.loadScene("Game", (err) => {
        if (err) {
          console.error("Error loading Game scene:", err);
          return;
        }
        console.log("Game scene loaded successfully.");
      });
      this.frameComplete = true;
    }
  }
}
