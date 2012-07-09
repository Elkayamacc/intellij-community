package com.jetbrains.python.inspections;

import com.google.common.collect.ImmutableSet;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.ui.ListEditForm;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.JDOMExternalizableStringList;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.util.Function;
import com.jetbrains.cython.psi.CythonCImportStatement;
import com.jetbrains.cython.psi.CythonFromCImportStatement;
import com.jetbrains.python.codeInsight.stdlib.PyStdlibUtil;
import com.jetbrains.python.packaging.*;
import com.jetbrains.python.packaging.ui.PyChooseRequirementsDialog;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.PyResolveUtil;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

/**
 * @author vlan
 */
public class PyPackageRequirementsInspection extends PyInspection {
  public JDOMExternalizableStringList ignoredPackages = new JDOMExternalizableStringList();

  @NotNull
  @Override
  public String getDisplayName() {
    return "Package requirements";
  }

  @Override
  public JComponent createOptionsPanel() {
    final ListEditForm form = new ListEditForm("Ignore packages", ignoredPackages);
    return form.getContentPanel();
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                        boolean isOnTheFly,
                                        @NotNull LocalInspectionToolSession session) {
    return new Visitor(holder, session, ignoredPackages);
  }

  @Nullable
  public static PyPackageRequirementsInspection getInstance(@NotNull PsiElement element) {
    final InspectionProfile inspectionProfile = InspectionProjectProfileManager.getInstance(element.getProject()).getInspectionProfile();
    final String toolName = PyPackageRequirementsInspection.class.getSimpleName();
    final InspectionProfileEntry inspectionTool = inspectionProfile.getInspectionTool(toolName, element);
    if (inspectionTool instanceof LocalInspectionToolWrapper) {
      final LocalInspectionToolWrapper profileEntry = (LocalInspectionToolWrapper)inspectionTool;
      final LocalInspectionTool tool = profileEntry.getTool();
      if (tool instanceof PyPackageRequirementsInspection) {
        return (PyPackageRequirementsInspection)tool;
      }
    }
    return null;
  }

  private static class Visitor extends PyInspectionVisitor {
    private final Set<String> myIgnoredPackages;

    public Visitor(@Nullable ProblemsHolder holder, @NotNull LocalInspectionToolSession session, Collection<String> ignoredPackages) {
      super(holder, session);
      myIgnoredPackages = ImmutableSet.copyOf(ignoredPackages);
    }

    @Override
    public void visitPyFile(PyFile node) {
      final Module module = ModuleUtil.findModuleForPsiElement(node);
      if (module != null) {
        if (isRunningPackagingTasks(module)) {
          return;
        }
        final Sdk sdk = PythonSdkType.findPythonSdk(module);
        if (sdk != null) {
          final List<PyRequirement> unsatisfied = findUnsatisfiedRequirements(module, sdk, myIgnoredPackages);
          if (unsatisfied != null && !unsatisfied.isEmpty()) {
            final boolean plural = unsatisfied.size() > 1;
            String msg = String.format("Package requirement%s %s %s not satisfied",
                                       plural ? "s" : "",
                                       requirementsToString(unsatisfied),
                                       plural ? "are" : "is");
            final Set<String> unsatisfiedNames = new HashSet<String>();
            for (PyRequirement req : unsatisfied) {
              unsatisfiedNames.add(req.getName());
            }
            final List<LocalQuickFix> quickFixes = new ArrayList<LocalQuickFix>();
            if (PyPackageManager.getInstance(sdk).hasPip()) {
              quickFixes.add(new PyInstallRequirementsFix(null, module, sdk, unsatisfied));
            }
            quickFixes.add(new IgnoreRequirementFix(unsatisfiedNames));
            registerProblem(node, msg,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING, null,
                            quickFixes.toArray(new LocalQuickFix[quickFixes.size()]));
          }
        }
      }
    }

    @Override
    public void visitPyFromImportStatement(PyFromImportStatement node) {
      if (node instanceof CythonFromCImportStatement) {
        return;
      }
      final PyReferenceExpression expr = node.getImportSource();
      if (expr != null) {
        checkPackageNameInRequirements(expr);
      }
    }

    @Override
    public void visitPyImportStatement(PyImportStatement node) {
      if (node instanceof CythonCImportStatement) {
        return;
      }
      for (PyImportElement element : node.getImportElements()) {
        final PyReferenceExpression expr = element.getImportReferenceExpression();
        if (expr != null) {
          checkPackageNameInRequirements(expr);
        }
      }
    }

    @Nullable
    private static Sdk findPythonSdk(@NotNull PsiElement element) {
      final Module module = ModuleUtil.findModuleForPsiElement(element);
      return PythonSdkType.findPythonSdk(module);
    }

    private void checkPackageNameInRequirements(@NotNull PyQualifiedExpression importedExpression) {
      final List<PyExpression> expressions = PyResolveUtil.unwindQualifiers(importedExpression);
      if (!expressions.isEmpty()) {
        final PyExpression packageReferenceExpression = expressions.get(0);
        final String packageName = packageReferenceExpression.getName();
        if (packageName != null && !myIgnoredPackages.contains(packageName)) {
          if (!ApplicationManager.getApplication().isUnitTestMode() && !PyPIPackageUtil.INSTANCE.isInPyPI(packageName)) {
            return;
          }
          final Collection<String> stdlibPackages = PyStdlibUtil.getPackages();
          if (stdlibPackages != null) {
            if (stdlibPackages.contains(packageName)) {
              return;
            }
          }
          if (PyPackageManager.PACKAGE_SETUPTOOLS.equals(packageName)) {
            return;
          }
          final Module module = ModuleUtil.findModuleForPsiElement(packageReferenceExpression);
          if (module != null) {
            Collection<PyRequirement> requirements = PyPackageManager.getRequirements(module);
            if (requirements != null) {
              final Sdk sdk = PythonSdkType.findPythonSdk(module);
              if (sdk != null) {
                requirements = getTransitiveRequirements(sdk, requirements, new HashSet<PyPackage>());
              }
              for (PyRequirement req : requirements) {
                if (packageName.equalsIgnoreCase(req.getName())) {
                  return;
                }
              }
              final PsiReference reference = packageReferenceExpression.getReference();
              if (reference != null) {
                final PsiElement element = reference.resolve();
                if (element != null) {
                  final PsiFile file = element.getContainingFile();
                  final VirtualFile virtualFile = file.getVirtualFile();
                  if (ModuleUtil.moduleContainsFile(module, virtualFile, false)) {
                    return;
                  }
                }
              }
              final List<LocalQuickFix> quickFixes = new ArrayList<LocalQuickFix>();
              if (sdk != null && PyPackageManager.getInstance(sdk).hasPip()) {
                quickFixes.add(new AddToRequirementsFix(module, packageName, LanguageLevel.forElement(importedExpression)));
              }
              quickFixes.add(new IgnoreRequirementFix(Collections.singleton(packageName)));
              registerProblem(packageReferenceExpression, String.format("Package '%s' is not listed in project requirements", packageName),
                              ProblemHighlightType.WEAK_WARNING, null,
                              quickFixes.toArray(new LocalQuickFix[quickFixes.size()]));
            }
          }
        }
      }
    }
  }

  @NotNull
  private static Set<PyRequirement> getTransitiveRequirements(@NotNull Sdk sdk, @NotNull Collection<PyRequirement> requirements,
                                                              @NotNull Set<PyPackage> visited) {
    final Set<PyRequirement> results = new HashSet<PyRequirement>(requirements);
    try {
      final List<PyPackage> packages = PyPackageManager.getInstance(sdk).getPackages();
      for (PyRequirement req : requirements) {
        final PyPackage pkg = req.match(packages);
        if (pkg != null) {
          visited.add(pkg);
          results.addAll(getTransitiveRequirements(sdk, pkg.getRequirements(), visited));
        }
      }
    }
    catch (PyExternalProcessException ignored) {
    }
    return results;
  }

  @NotNull
  private static String requirementsToString(@NotNull List<PyRequirement> requirements) {
    return StringUtil.join(requirements, new Function<PyRequirement, String>() {
      @Override
      public String fun(PyRequirement requirement) {
        return String.format("'%s'", requirement.toString());
      }
    }, ", ");
  }

  @Nullable
  private static List<PyRequirement> findUnsatisfiedRequirements(@NotNull Module module, @NotNull Sdk sdk,
                                                                 @NotNull Set<String> ignoredPackages) {
    final PyPackageManager manager = PyPackageManager.getInstance(sdk);
    List<PyRequirement> requirements = PyPackageManager.getRequirements(module);
    if (requirements != null) {
      final List<PyPackage> packages;
      try {
        packages = manager.getPackages();
      }
      catch (PyExternalProcessException ignored) {
        return null;
      }
      final List<PyRequirement> unsatisfied = new ArrayList<PyRequirement>();
      for (PyRequirement req : requirements) {
        if (!ignoredPackages.contains(req.getName()) && req.match(packages) == null) {
          unsatisfied.add(req);
        }
      }
      return unsatisfied;
    }
    return null;
  }

  private static void setRunningPackagingTasks(@NotNull Module module, boolean value) {
    module.putUserData(PyPackageManager.RUNNING_PACKAGING_TASKS, value);
  }

  private static boolean isRunningPackagingTasks(@NotNull Module module) {
    final Boolean value = module.getUserData(PyPackageManager.RUNNING_PACKAGING_TASKS);
    return value != null && value;
  }

  public static class PyInstallRequirementsFix implements LocalQuickFix {
    @NotNull private String myName;
    @NotNull private final Module myModule;
    @NotNull private Sdk mySdk;
    @NotNull private final List<PyRequirement> myUnsatisfied;

    public PyInstallRequirementsFix(@Nullable String name, @NotNull Module module, @NotNull Sdk sdk,
                                    @NotNull List<PyRequirement> unsatisfied) {
      final boolean plural = unsatisfied.size() > 1;
      myName = name != null ? name : String.format("Install requirement%s", plural ? "s" : "");
      myModule = module;
      mySdk = sdk;
      myUnsatisfied = unsatisfied;
    }

    @NotNull
    @Override
    public String getName() {
      return myName;
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return myName;
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
      final List<PyRequirement> chosen;
      if (myUnsatisfied.size() > 1) {
        final PyChooseRequirementsDialog dialog = new PyChooseRequirementsDialog(project, myUnsatisfied);
        chosen = dialog.showAndGetResult();
      }
      else {
        chosen = myUnsatisfied;
      }
      if (chosen.isEmpty()) {
        return;
      }
      final PyPackageManager.UI ui = new PyPackageManager.UI(project, mySdk, new PyPackageManager.UI.Listener() {
        @Override
        public void started() {
          setRunningPackagingTasks(myModule, true);
        }

        @Override
        public void finished(List<PyExternalProcessException> exceptions) {
          setRunningPackagingTasks(myModule, false);
        }
      });
      ui.install(chosen, Collections.<String>emptyList());
    }
  }

  private static class IgnoreRequirementFix implements LocalQuickFix {
    @NotNull private final Set<String> myPackageNames;

    public IgnoreRequirementFix(@NotNull Set<String> packageNames) {
      myPackageNames = packageNames;
    }

    @NotNull
    @Override
    public String getName() {
      final boolean plural = myPackageNames.size() > 1;
      return String.format("Ignore requirement%s", plural ? "s" : "");
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return getName();
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      if (element != null) {
        final PyPackageRequirementsInspection inspection = PyPackageRequirementsInspection.getInstance(element);
        if (inspection != null) {
          final JDOMExternalizableStringList ignoredPackages = inspection.ignoredPackages;
          boolean changed = false;
          for (String name : myPackageNames) {
            if (!ignoredPackages.contains(name)) {
              ignoredPackages.add(name);
              changed = true;
            }
          }
          if (changed) {
            final InspectionProfile profile = InspectionProjectProfileManager.getInstance(project).getInspectionProfile();
            InspectionProfileManager.getInstance().fireProfileChanged(profile);
          }
        }
      }
    }
  }

  private static class AddToRequirementsFix implements LocalQuickFix {
    @Nullable private final PyListLiteralExpression mySetupPyRequires;
    @Nullable private final VirtualFile myRequirementsTxt;
    @Nullable private final PyArgumentList mySetupArgumentList;
    @NotNull private final String myPackageName;
    @NotNull private final LanguageLevel myLanguageLevel;

    private AddToRequirementsFix(@NotNull Module module, @NotNull String packageName, @NotNull LanguageLevel languageLevel) {
      myPackageName = packageName;
      myLanguageLevel = languageLevel;
      myRequirementsTxt = PyPackageUtil.findRequirementsTxt(module);
      mySetupPyRequires = PyPackageUtil.findSetupPyRequires(module);
      final PyFile setupPy = PyPackageUtil.findSetupPy(module);
      if (setupPy != null) {
        final PyCallExpression setupCall = PyPackageUtil.findSetupCall(setupPy);
        if (setupCall != null) {
          mySetupArgumentList = setupCall.getArgumentList();
        }
        else {
          mySetupArgumentList = null;
        }
      }
      else {
        mySetupArgumentList = null;
      }
    }

    @NotNull
    @Override
    public String getName() {
      final String target;
      if (myRequirementsTxt != null) {
        target = myRequirementsTxt.getName();
      }
      else if (mySetupPyRequires != null || mySetupArgumentList != null) {
        target = "setup.py";
      }
      else {
        target = "project requirements";
      }
      return String.format("Add requirement '%s' to %s", myPackageName, target);
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return getName();
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
      CommandProcessor.getInstance().executeCommand(project, new Runnable() {
        @Override
        public void run() {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
              if (myRequirementsTxt != null) {
                if (myRequirementsTxt.isWritable()) {
                  final Document document = FileDocumentManager.getInstance().getDocument(myRequirementsTxt);
                  if (document != null) {
                    document.insertString(0, myPackageName + "\n");
                  }
                }
              }
              else {
                final PyElementGenerator generator = PyElementGenerator.getInstance(project);
                if (mySetupPyRequires != null) {
                  if (mySetupPyRequires.getContainingFile().isWritable()) {
                    final String text = String.format("'%s'", myPackageName);
                    final PyExpression generated = generator.createExpressionFromText(myLanguageLevel, text);
                    mySetupPyRequires.add(generated);
                  }
                }
                else if (mySetupArgumentList != null) {
                  final PyKeywordArgument requiresArg = generateRequiresKwarg(generator);
                  if (requiresArg != null) {
                    mySetupArgumentList.addArgument(requiresArg);
                  }
                }
              }
            }

            @Nullable
            private PyKeywordArgument generateRequiresKwarg(PyElementGenerator generator) {
              final String text = String.format("foo(requires=['%s'])", myPackageName);
              final PyExpression generated = generator.createExpressionFromText(myLanguageLevel, text);
              PyKeywordArgument installRequiresArg = null;
              if (generated instanceof PyCallExpression) {
                final PyCallExpression foo = (PyCallExpression)generated;
                for (PyExpression arg : foo.getArguments()) {
                  if (arg instanceof PyKeywordArgument) {
                    final PyKeywordArgument kwarg = (PyKeywordArgument)arg;
                    if ("requires".equals(kwarg.getKeyword())) {
                      installRequiresArg = kwarg;
                    }
                  }
                }
              }
              return installRequiresArg;
            }
          });
        }
      }, getName(), null);
    }
  }
}
