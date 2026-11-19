package dev.mixeddeno.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** Indexes npm packages that live in the global Deno cache (outside the project) so navigation works. */
class LibraryProvider : AdditionalLibraryRootsProvider() {
  override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
    val roots = roots(project)
    if (roots.isEmpty()) return emptyList()
    return listOf(DenoNpmLibrary(roots))
  }

  override fun getRootsToWatch(project: Project): Collection<VirtualFile> = roots(project)

  private fun roots(project: Project): List<VirtualFile> {
    val base = MixedDenoService.get(project).root ?: return emptyList()
    return MixedDenoService.get(project).externalRoots
      .filter { !it.startsWith(base) }
      .mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }
  }
}

class DenoNpmLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary() {
  override fun getSourceRoots(): Collection<VirtualFile> = roots
  override fun equals(other: Any?) = other is DenoNpmLibrary && other.roots == roots
  override fun hashCode() = roots.hashCode()
}
