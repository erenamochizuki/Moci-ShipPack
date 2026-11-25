package data.hullmods.ships.hiryu;

/** 切换为「召回指令」战术系统（舰体：Moci_Hiryu_Recall）。逻辑见 {@link Moci_Pa_HiryuSystemMode}。 */
public class Moci_Pa_HiryuSystemRecall extends Moci_Pa_HiryuSystemMode {
  public Moci_Pa_HiryuSystemRecall() {
    super(Mode.RECALL);
  }
}
