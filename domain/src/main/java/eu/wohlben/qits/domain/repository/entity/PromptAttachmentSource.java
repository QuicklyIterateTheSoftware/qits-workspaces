package eu.wohlben.qits.domain.repository.entity;

/**
 * Where a prompt attachment came from — a drawing on the Sketch tab ({@link #SKETCH}) or an image
 * pasted from the clipboard ({@link #PASTE}). Stored as the enum name; the composing UI uses it
 * only to label the attachment ("Sketch 1" / "Pasted image 1").
 */
public enum PromptAttachmentSource {
  SKETCH,
  PASTE
}
