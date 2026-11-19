package dev.mixeddeno.ide

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Shows generator problems (Deno-only module imported from browser code, specifier missing from import map). */
class ImportInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    val path = file.virtualFile?.toNioPath()?.toString() ?: return null
    val problems = MixedDenoService.get(file.project).problemsByFile[path] ?: return null
    val text = file.text
    val result = mutableListOf<ProblemDescriptor>()
    for (p in problems) {
      if (p.specifier.isEmpty()) continue
      var idx = text.indexOf("\"${p.specifier}\"")
      if (idx < 0) idx = text.indexOf("'${p.specifier}'")
      if (idx < 0) continue
      result += manager.createProblemDescriptor(
        file, TextRange(idx, idx + p.specifier.length + 2), p.message,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly,
      )
    }
    return result.toTypedArray()
  }
}
