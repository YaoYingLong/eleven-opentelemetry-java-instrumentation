/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.muzzle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import javax.inject.Inject

abstract class MuzzleExtension
// @Inject用于依赖注入，通常用于注入Gradle提供的服务或对象，此处是一个构造函数
@Inject constructor(private val objectFactory: ObjectFactory) {

  // internal关键字限制了属性的可见性，仅在同一模块中可见
  internal abstract val directives: ListProperty<MuzzleDirective>

  // 允许用户通过 pass 和 fail 方法定义哪些指令应通过或失败
  fun pass(action: Action<MuzzleDirective>) {
    // ObjectFactory用于创建新的对象实例，创建MuzzleDirective对象实例
    val pass = objectFactory.newInstance(MuzzleDirective::class.java)
    action.execute(pass)
    pass.assertPass.set(true)
    directives.add(pass)
  }

  // 允许用户通过 pass 和 fail 方法定义哪些指令应通过或失败
  fun fail(action: Action<MuzzleDirective>) {
    val fail = objectFactory.newInstance(MuzzleDirective::class.java)
    action.execute(fail)
    fail.assertPass.set(false)
    directives.add(fail)
  }
}
