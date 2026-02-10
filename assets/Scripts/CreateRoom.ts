import {_decorator, Component, director} from "cc";

const { ccclass, property } = _decorator;

@ccclass("CreateRoom")
export class CreateRoom extends Component {
  start() {}

  update(deltaTime: number) {}

  onCreateRoomButtonClick() {
    // Logic to create a room
    console.log("Create Room button clicked");
    // Here you would typically send a request to your server to create a room
    // and handle the response accordingly.

    // director.loadScene("02-Game", () => {
    //   console.log("Game scene loaded successfully");
    //   // You can also pass any necessary data to the room scene here if needed.
    // });
  }

  onJoinRoomButtonClick() {
    // Logic to join a room
    console.log("Join Room button clicked");
    // Similar to creating a room, you would send a request to your server
    // to join an existing room and handle the response accordingly.

    director.loadScene("Join", (err) => {
      if (err) {
        console.error("Error loading Game scene:", err);
        return;
      }
      console.log("Game scene loaded successfully.");
    });
  }
}
