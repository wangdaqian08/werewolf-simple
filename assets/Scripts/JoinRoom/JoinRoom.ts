import {_decorator, Component, EditBox, Node} from "cc";

const { ccclass, property } = _decorator;

@ccclass("JoinRoom")
export class JoinRoom extends Component {
  @property(Node)
  private seatPanel: Node = null!;

  @property(Node)
  private actionButton: Node = null!;

  @property(Node)
  private joinButton: Node = null!;

  @property(EditBox)
  private roomNumberInput: EditBox = null!;

  // The correct room number
  private readonly CORRECT_ROOM_NUMBER: string = "1234";

  start() {
    // Initially hide the seat panel and action button
    this.seatPanel.active = false;
    this.actionButton.active = false;
    this.joinButton.action = true;
    this.joinButton.on(
      Node.EventType.MOUSE_DOWN,
      this.onJoinButtonClicked,
      this
    );
  }

  update(deltaTime: number) {
    // No need for update logic in this case
  }

  /**
   * Called when the join button is clicked
   */
  onJoinButtonClicked() {
    const enteredRoomNumber = this.roomNumberInput.string;

    if (enteredRoomNumber === this.CORRECT_ROOM_NUMBER) {
      this.joinRoom();
    } else {
      console.log("Incorrect room number!");
      // You could add feedback here like shaking the input or showing an error message
    }
  }

  /**
   * Handles the room joining process when the correct number is entered
   */
  private joinRoom() {
    // Hide the room number component
    this.roomNumberInput.node.active = false;

    this.joinButton.active = false;
    this.joinButton.off(
      Node.EventType.MOUSE_DOWN,
      this.onJoinButtonClicked,
      this
    );
    this.roomNumberInput.active = false;

    // Show the seat panel and action button
    this.seatPanel.active = true;
    this.actionButton.active = true;

    // Additional room joining logic can be added here
    console.log("Successfully joined room!");
  }
}
