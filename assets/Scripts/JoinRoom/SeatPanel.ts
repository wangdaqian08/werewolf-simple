import {_decorator, Button, Component, instantiate, Label, Layout, Node, Prefab,} from "cc";

const { ccclass, property } = _decorator;

@ccclass("SeatPanel")
export class SeatPanel extends Component {
  @property(Prefab)
  seatPrefab: Prefab | null = null;

  @property(Prefab)
  seatSelectedPrefab: Prefab | null = null;

  @property(Node)
  layoutNode: Node | null = null;

  @property(Node)
  actionButton: Button | null = null;

  private seats: Node[] = [];
  private selectedIndex: number | null = null;
  // private lockedIndex: number | null = null;
  private visualSelectedIndex: number | null = null; // Track visually selected seat

  private totalSeats: number = 0;

  start() {
    // 这里可以从服务器或房间配置获取人数
    this.initSeats(6); // 比如房间 6 个座位
    this.updateButtonState();
    this.actionButton?.on(
      Button.EventType.CLICK,
      this.onActionButtonClick,
      this
    );
  }

  initSeats(count: number) {
    this.totalSeats = count;
    this.layoutNode.removeAllChildren();
    this.seats = [];

    for (let i = 0; i < count; i++) {
      const seat = instantiate(this.seatPrefab!);
      this.setSeatNumber(seat, i);
      this.layoutNode.addChild(seat);
      this.seats.push(seat);

      seat.on(Node.EventType.MOUSE_DOWN, () => {
        this.onSeatSelected(i);
      });
    }
    this.layoutNode!.getComponent(Layout)?.updateLayout();
  }

  private onSeatSelected(index: number) {
    if (this.selectedIndex !== null) return;

    // If there's already a visually selected seat, restore it first
    if (this.visualSelectedIndex !== null) {
      this.restoreSeat(this.visualSelectedIndex);
    }

    // 获取当前 seat 的位置
    const oldSeatNode = this.seats[index];

    // 创建选中状态的 seat
    const selectedSeat = instantiate(this.seatSelectedPrefab!);
    this.setSeatNumber(selectedSeat, index); // 保留编号

    //
    // this.layoutNode.insertChild(selectedSeat, index);
    // oldSeatNode.destroy();

    // 替换旧的 seat
    const siblingIndex = oldSeatNode.getSiblingIndex();
    this.layoutNode.insertChild(selectedSeat, siblingIndex);
    oldSeatNode.removeFromParent();
    oldSeatNode.destroy();

    // 更新数组引用
    this.seats[index] = selectedSeat;

    // 保存选中 index
    this.visualSelectedIndex = index;
    this.updateButtonState();
  }

  /** 恢复为普通 seat */
  private restoreSeat(index: number) {
    const selectedSeatNode = this.seats[index];
    const normalSeat = instantiate(this.seatPrefab!);
    this.setSeatNumber(normalSeat, index);

    // this.layoutNode?.insertChild(normalSeat, index);
    // selectedSeatNode.destroy();

    // Replace the selected seat properly
    const siblingIndex = selectedSeatNode.getSiblingIndex();
    this.layoutNode?.insertChild(normalSeat, siblingIndex);
    selectedSeatNode.removeFromParent();
    selectedSeatNode.destroy();

    this.seats[index] = normalSeat;

    // 绑定事件
    normalSeat.on(Node.EventType.MOUSE_DOWN, () => {
      this.onSeatSelected(index);
    });
    // Clear visual selection if this is the visually selected seat
    if (this.visualSelectedIndex === index) {
      this.visualSelectedIndex = null;
    }
  }

  private onActionButtonClick() {
    if (this.selectedIndex === null) {
      // Assign selectedIndex when "坐下" is clicked
      if (this.visualSelectedIndex !== null) {
        this.selectedIndex = this.visualSelectedIndex;
      }
    } else {
      // Stand up
      this.restoreSeat(this.selectedIndex);
      this.selectedIndex = null;
    }
    this.updateButtonState();
  }

  /** 更新按钮文字 */
  private updateButtonState() {
    if (!this.actionButton) return;
    const label = this.actionButton.getComponentInChildren(Label);
    if (label) {
      label.string = this.selectedIndex === null ? "坐下" : "站起来";
    }
  }

  private setSeatNumber(seatNode: Node, index: number) {
    const numberNode = seatNode.getChildByName("number");
    if (numberNode) {
      const label = numberNode.getComponent(Label);
      if (label) {
        label.string = (index + 1).toString();
      }
    }
  }
}
