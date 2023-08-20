package net.neoforged.gradle.neoform

import net.neoforged.gradle.common.util.SerializationUtils
import net.neoforged.trainingwheels.gradle.functional.BuilderBasedTestSpecification
import org.gradle.testkit.runner.TaskOutcome

class FunctionalTests extends BuilderBasedTestSpecification {

    @Override
    protected void configurePluginUnderTest() {
        pluginUnderTest = "net.neoforged.gradle.neoform";
        injectIntoAllProject = true;
    }

    @Override
    protected File getTestTempDirectory() {
        return new File("./tests/")
    }

    def "a mod with neoform as dependency can run the apply official mappings task"() {
        given:
        def project = create "neoform-has-runnable-patch-task", {
            it.build("""
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            
            dependencies {
                implementation 'net.minecraft:neoform_client:+'
            }
            """)
        }

        when:
        def run = project.run { it.tasks(':neoFormApplyOfficialMappings') }

        then:
        run.task(':neoFormApplyOfficialMappings').outcome == TaskOutcome.SUCCESS
    }

    def "neoform applies user ATs and allows remapped compiling"() {
        given:
        def project = create "neoform-compile-with-ats", {
            it.build("""
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            
            minecraft {
                accessTransformers {
                    entry "public net.minecraft.client.Minecraft LOGGER # searchRegistry"
                }
            }
            
            dependencies {
                implementation 'net.minecraft:neoform_client:+'
            }
            """)
            it.file("src/main/java/net/neoforged/gradle/neoform/FunctionalTests.java", """
            package net.neoforged.gradle.neoform;
            
            import net.minecraft.client.Minecraft;
            
            public class FunctionalTests {
                public static void main(String[] args) {
                    System.out.println(Minecraft.LOGGER.getClass().toString());
                }
            }
            """)
        }

        when:
        def run = project.run { it.tasks('build') }

        then:
        run.task('build').outcome == TaskOutcome.SUCCESS
    }

    def "neoform re-setup uses a build-cache" () {
        given:
        def project = create "neoform-compile-with-ats", {
            it.build("""
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            
            dependencies {
                implementation 'net.minecraft:neoform_client:+'
            }
            """)

            it.file("src/main/java/net/neoforged/gradle/neoform/FunctionalTests.java", """
            package net.neoforged.gradle.neoform;
            
            import net.minecraft.client.Minecraft;
            
            public class FunctionalTests {
                public static void main(String[] args) {
                    System.out.println(Minecraft.getInstance().getClass().toString());
                }
            }
            """)
        }

        when:
        def run = project.run { it.tasks('build') }

        then:
        run.task('build').outcome == TaskOutcome.SUCCESS

        when:
        new File(project.getProjectDir(), 'build').deleteDir()
        def secondRun = project.run {it.tasks('build') }

        then:
        secondRun.task('build').outcome == TaskOutcome.SUCCESS
        secondRun.task('neoFormClientRecompile').outcome == TaskOutcome.FROM_CACHE
    }


}
