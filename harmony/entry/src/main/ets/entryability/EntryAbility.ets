import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';

export default class EntryAbility extends UIAbility {
  onWindowStageCreate(windowStage: window.WindowStage): void {
    void windowStage.loadContent('pages/Index').catch((error: Error) => {
      console.error(`页面加载失败：${error.message}`);
    });
  }
}
