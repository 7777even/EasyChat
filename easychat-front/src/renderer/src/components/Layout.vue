<template>
  <div class="layout-container">
    <div class="left-side-inner">
      <slot name="left-content"> </slot>
    </div>
    <div class="right-content">
      <slot name="right-content"> </slot>
    </div>
  </div>
</template>

<script setup>
</script>

<style lang="scss" scoped>
.layout-container {
  display: flex;
  .left-side-inner {
    width: 250px;
    background: var(--ec-left-bg);
    border-color: var(--ec-border);
    border-style: solid;
    border-width: 0px 1px 0px 0px;
  }
  .right-content {
    flex: 1;
    height: calc(100vh - 2px);
    background: var(--ec-right-bg);
    width: 100%;
  }
}
</style>
