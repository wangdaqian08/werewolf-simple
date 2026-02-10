import {_decorator, Component, EditBox} from "cc";

const { ccclass, property } = _decorator;

@ccclass("NumberInput")
export class NumberInput extends Component {
  @property(EditBox)
  editBox: EditBox = null!;

  onLoad() {
    if (!this.editBox) {
      console.error("EditBox 未绑定！");
      return;
    }
    this.editBox.node.on("text-changed", this.onTextChanged, this);
  }

  // 当输入内容变化时调用
  onTextChanged(editBox: EditBox) {
    // 过滤非数字
    const onlyNums = editBox.string.replace(/\D/g, "");
    if (onlyNums !== editBox.string) {
      editBox.string = onlyNums;
    }
    console.log("当前数字：", editBox.string);
  }
}
