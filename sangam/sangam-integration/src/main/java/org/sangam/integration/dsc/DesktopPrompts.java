// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * Owner decision 165: the agent's local dialogs. The token PIN is read into a password field, handed to the token and
 * wiped; it is never stored, logged or sent. Every signature shows the requesting origin, the document, its digest and
 * the signer, and is refused unless the user presses the sign button. Without a desktop nothing is asked and the agent
 * does not start.
 */
public final class DesktopPrompts implements CallbackHandler, DscLoopbackAgent.Approval {

    private DesktopPrompts() {
    }

    /** The desktop prompts, or an exception when there is no desktop to ask on. */
    public static DesktopPrompts require() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("the DSC agent needs a desktop for the PIN and approval dialogs");
        }
        return new DesktopPrompts();
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
        for (Callback c : callbacks) {
            if (!(c instanceof PasswordCallback pc)) {
                throw new UnsupportedCallbackException(c);
            }
            AtomicReference<char[]> pin = new AtomicReference<>();
            onDesktop(() -> {
                JPasswordField field = new JPasswordField(20);
                int r = JOptionPane.showConfirmDialog(null, new Object[]{pc.getPrompt(), field}, "Signing token PIN",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                char[] typed = field.getPassword();
                field.setText("");
                if (r == JOptionPane.OK_OPTION) {
                    pin.set(typed);
                } else {
                    Arrays.fill(typed, '\0');
                }
            });
            char[] value = pin.get();
            pc.setPassword(value);   // null = cancelled: the token login fails and nothing is signed
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
    }

    @Override
    public boolean approve(DscLoopbackAgent.SignRequest r) {
        AtomicBoolean yes = new AtomicBoolean(false);
        onDesktop(() -> {
            String text = "A Sangam page asks to sign a document with your certificate.\n\n"
                    + "Page: " + r.origin() + "\n"
                    + "Document: " + r.documentName() + "\n"
                    + "Document digest (SHA-256): " + r.preparedDigestSha256() + "\n"
                    + "Certificate: " + r.signerSubject() + "\n\n"
                    + "Sign only if you started this signature.";
            Object[] options = {"Sign", "Do not sign"};
            int choice = JOptionPane.showOptionDialog(null, text, "Sign document", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            yes.set(choice == 0);
        });
        return yes.get();
    }

    private static void onDesktop(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("dialog failed", e.getCause());
        }
    }
}
