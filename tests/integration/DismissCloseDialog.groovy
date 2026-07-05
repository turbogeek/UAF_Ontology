// DismissCloseDialog.groovy — utility (not a test)
// A WM_CLOSE was sent to Cameo; a save-confirmation dialog is likely blocking shutdown.
// Enumerates visible dialogs, journals every button, then clicks "Don't Save"/"No"
// (never "Save" - integration fixtures must not be persisted into the host project).
import javax.swing.AbstractButton
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'DismissCloseDialog.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

def buttons = []
def collect
collect = { java.awt.Component c, List out ->
    if (c instanceof AbstractButton) { out << c }
    if (c instanceof java.awt.Container) { c.getComponents().each { collect(it, out) } }
}

SwingUtilities.invokeAndWait {
    java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
        def title = (dlg.respondsTo('getTitle') ? dlg.getTitle() : '?')
        def dlgButtons = []
        collect(dlg, dlgButtons)
        diag('DIALOG: "' + title + '" buttons=' + dlgButtons.collect { it.getText() })
        buttons.addAll(dlgButtons.collect { [dialog: title, button: it] })
    }
}
if (buttons.isEmpty()) {
    diag('no visible dialogs found')
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().findAll { it.isVisible() }.each { w ->
            diag('WINDOW: ' + w.getClass().getSimpleName() + ' "' + (w.respondsTo('getTitle') ? w.getTitle() : '') + '"')
        }
    }
    return
}

// Prefer the discard option; never click Save.
def preferred = ["Don't Save", "Don`t Save", "No", "Discard", "Close Anyway"]
def target = null
for (String label : preferred) {
    target = buttons.find { it.button.getText()?.trim()?.equalsIgnoreCase(label) }
    if (target != null) { break }
}
if (target == null) {
    diag('no safe discard button found - NOT clicking anything')
} else {
    diag('clicking "' + target.button.getText() + '" on dialog "' + target.dialog + '"')
    SwingUtilities.invokeAndWait { target.button.doClick() }
    diag('clicked')
}
