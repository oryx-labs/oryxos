<template>
  <DefaultTheme.Layout>
    <template #doc-after v-if="frontmatter.layout !== 'home'">
    </template>
  </DefaultTheme.Layout>
</template>

<script setup>
import DefaultTheme from 'vitepress/theme'
import { useData } from 'vitepress'

const { frontmatter } = useData()
</script>
