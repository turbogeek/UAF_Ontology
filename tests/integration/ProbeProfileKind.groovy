// ProbeProfileKind.groovy — compare how "Semantic Alignment Profile" is mounted vs the
// stock "UAF Profile" / "SoaML Profile": editability, read-only, module membership. Reveals
// whether our profile behaves like a proper read-only used module or a shared/embedded one.
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeProfileKind.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('=== probe-profile-kind START ===')
def app = Application.getInstance()
try {
    SwingUtilities.invokeAndWait {
        def prj = app.getProject()
        if (prj == null) { diag('no project'); return }
        def model = prj.getPrimaryModel()

        // Try the module API to list mounted modules by name.
        try {
            def MS = Class.forName('com.nomagic.magicdraw.core.modules.ModulesService')
            diag('ModulesService static methods: ' + MS.getMethods().findAll { java.lang.reflect.Modifier.isStatic(it.getModifiers()) }.collect { it.getName() }.unique().sort())
        } catch (Throwable t) { diag('ModulesService introspection failed: ' + t) }

        // Compare profile-like packages directly under the model.
        def report = { el ->
            if (el == null) { return }
            def cls = el.getClass().getSimpleName()
            def editable = 'n/a'; try { editable = el.isEditable() } catch (Throwable ignored) {}
            def readOnly = 'n/a'; try { readOnly = el.respondsTo('isReadOnly') ? el.isReadOnly() : 'no-method' } catch (Throwable ignored) {}
            def owner = el.getOwner()?.getClass()?.getSimpleName()
            diag(String.format('  %-32s cls=%-16s editable=%s readOnly=%s ownerCls=%s',
                    el.getName(), cls, String.valueOf(editable), String.valueOf(readOnly), owner))
        }

        diag('--- profile-like packages under the model ---')
        model.getOwnedElement().findAll {
            it.respondsTo('getName') && it.getName() != null &&
                    (it.getName().endsWith('Profile') || it.getName().contains('Customization'))
        }.each { report(it) }

        // The stereotypes themselves (should both be read-only used-module elements).
        diag('--- stereotypes ---')
        def sa = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
        def ch = StereotypesHelper.getStereotype(prj, 'Challenge')
        [['SemanticAlignment', sa], ['Challenge(UAF)', ch]].each { pair ->
            def s = pair[1]
            if (s == null) { diag('  ' + pair[0] + ' = null'); return }
            def editable = 'n/a'; try { editable = s.isEditable() } catch (Throwable ignored) {}
            def invis = 'n/a'; try { invis = StereotypesHelper.isInvisible(s) } catch (Throwable ignored) {}
            diag(String.format('  %-20s editable=%s invisible=%s ownerCls=%s',
                    pair[0], String.valueOf(editable), String.valueOf(invis), s.getOwner()?.getClass()?.getSimpleName()))
        }
    }
} catch (Throwable t) { def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag('threw\n' + sw) }
diag('=== probe-profile-kind DONE ===')
